package com.hezhangjian.ontology.projection.messaging;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.IndexRebuildCommand;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import com.hezhangjian.ontology.projection.IndexRebuildProcessor;
import com.hezhangjian.ontology.projection.MutationBatchProcessor;
import com.hezhangjian.ontology.projection.ProjectionProcessor;
import com.hezhangjian.ontology.projection.config.ProjectionProperties;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class PulsarProjectionConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PulsarProjectionConsumer.class);
    private static final String DLQ_TOPIC = "persistent://platform/dlq/projection-events";
    private static final List<String> INPUT_TOPICS = List.of(
            "persistent://platform/commands/mutation-batches",
            "persistent://platform/index/rebuild-events",
            "persistent://platform/ingestion/object-events",
            "persistent://platform/ingestion/relation-events");

    private final ProjectionProperties properties;
    private final ObjectMapper objectMapper;
    private final ProjectionProcessor projectionProcessor;
    private final MutationBatchProcessor mutationProcessor;
    private final IndexRebuildProcessor rebuildProcessor;
    private final ControlPlaneRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("projection-consumer").factory());
    private volatile boolean running;
    private PulsarClient client;
    private Consumer<byte[]> consumer;
    private Producer<byte[]> dlqProducer;

    public PulsarProjectionConsumer(
            ProjectionProperties properties,
            ObjectMapper objectMapper,
            ProjectionProcessor projectionProcessor,
            MutationBatchProcessor mutationProcessor,
            IndexRebuildProcessor rebuildProcessor,
            ControlPlaneRepository repository) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.projectionProcessor = projectionProcessor;
        this.mutationProcessor = mutationProcessor;
        this.rebuildProcessor = rebuildProcessor;
        this.repository = repository;
    }

    @Override
    public void start() {
        try {
            client = PulsarClient.builder().serviceUrl(properties.pulsarUrl().toString()).build();
            dlqProducer = client.newProducer(Schema.BYTES)
                    .topic(DLQ_TOPIC)
                    .enableBatching(false)
                    .create();
            consumer = client.newConsumer(Schema.BYTES)
                    .topics(INPUT_TOPICS)
                    .subscriptionName("ontology-projection-v1")
                    .subscriptionType(SubscriptionType.Key_Shared)
                    .negativeAckRedeliveryDelay(properties.retryDelay().toMillis(), TimeUnit.MILLISECONDS)
                    .subscribe();
            running = true;
            executor.submit(this::consume);
            log.info("Projection consumer started for {} topics", INPUT_TOPICS.size());
        } catch (Exception exception) {
            closeResources();
            throw new IllegalStateException("Cannot start Pulsar projection consumer", exception);
        }
    }

    private void consume() {
        while (running) {
            try {
                Message<byte[]> message = consumer.receive(1, TimeUnit.SECONDS);
                if (message != null) {
                    handle(message);
                }
            } catch (Exception exception) {
                if (running) {
                    log.error("Projection receive loop failed", exception);
                }
            }
        }
    }

    private void handle(Message<byte[]> message) {
        UUID eventId = null;
        try {
            String topic = message.getTopicName();
            String messageId = message.getMessageId().toString();
            if (topic.contains("/object-events") || topic.contains("/relation-events")) {
                OntologyEventEnvelope event = objectMapper.readValue(message.getData(), OntologyEventEnvelope.class);
                eventId = event.eventId();
                projectionProcessor.process(topic, messageId, event);
            } else if (topic.contains("/mutation-batches")) {
                OntologyMutationBatch batch = objectMapper.readValue(message.getData(), OntologyMutationBatch.class);
                mutationProcessor.process(topic, messageId, batch);
            } else if (topic.contains("/rebuild-events")) {
                IndexRebuildCommand command = objectMapper.readValue(message.getData(), IndexRebuildCommand.class);
                rebuildProcessor.rebuild(command);
            } else {
                throw new ProjectionException("TOPIC_UNSUPPORTED", "Unsupported projection topic", false);
            }
            consumer.acknowledge(message);
        } catch (Exception exception) {
            ProjectionException failure = classify(exception);
            log.warn("Projection message failed with {}", failure.code(), exception);
            int attempt = message.getRedeliveryCount() + 1;
            repository.recordFailure(eventId, failure.code(), failure.retryable(), attempt, failure.getMessage());
            if (failure.retryable() && attempt < properties.maxRetries()) {
                log.warn("Projection attempt {} failed and will be retried: {}", attempt, failure.code());
                consumer.negativeAcknowledge(message);
                return;
            }
            sendToDlq(message, failure, attempt);
            if (eventId != null) {
                repository.dlq(eventId, failure.code(), failure.getMessage());
            }
            acknowledgeAfterDlq(message);
        }
    }

    private ProjectionException classify(Exception exception) {
        if (exception instanceof ProjectionException projectionException) {
            return projectionException;
        }
        if (!(exception instanceof JacksonException)) {
            return new ProjectionException(
                    "PROJECTION_PROCESSING_FAILED",
                    "Projection processing failed unexpectedly",
                    true,
                    exception);
        }
        return new ProjectionException(
                "MESSAGE_INVALID",
                "Projection message could not be decoded or processed",
                false,
                exception);
    }

    private void sendToDlq(Message<byte[]> message, ProjectionException failure, int attempt) {
        try {
            var builder = dlqProducer.newMessage()
                    .properties(Map.of(
                            "error-code", failure.code(),
                            "original-message-id", message.getMessageId().toString(),
                            "original-topic", message.getTopicName(),
                            "projection-attempt", Integer.toString(attempt)))
                    .value(message.getData());
            if (message.hasKey()) {
                builder.key(message.getKey());
            }
            builder.send();
        } catch (Exception dlqException) {
            log.error("DLQ publish failed; original message will be retried", dlqException);
            consumer.negativeAcknowledge(message);
            throw new ProjectionException("DLQ_UNAVAILABLE", "Projection DLQ is unavailable", true, dlqException);
        }
    }

    private void acknowledgeAfterDlq(Message<byte[]> message) {
        try {
            consumer.acknowledge(message);
        } catch (Exception exception) {
            log.error("Failed to acknowledge message after DLQ publish", exception);
            consumer.negativeAcknowledge(message);
        }
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        closeResources();
    }

    private void closeResources() {
        close(consumer);
        close(dlqProducer);
        close(client);
    }

    private void close(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception exception) {
                log.warn("Failed to close projection resource", exception);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
