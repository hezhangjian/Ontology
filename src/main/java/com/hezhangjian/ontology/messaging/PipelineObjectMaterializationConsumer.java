package com.hezhangjian.ontology.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.config.PulsarProperties;
import com.hezhangjian.ontology.service.PipelineObjectMaterializationService;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.DeadLetterPolicy;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "ontology.messaging",
        name = "consumers-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class PipelineObjectMaterializationConsumer implements SmartLifecycle {
    private static final Logger log =
            LoggerFactory.getLogger(PipelineObjectMaterializationConsumer.class);
    private static final String TOPIC =
            "persistent://platform/ingestion/pipeline-object-commands";

    private final PipelineObjectMaterializationService materializations;
    private final ObjectMapper json;
    private final String serviceUrl;
    private final String listenerName;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("pipeline-object-materialization-consumer").factory());
    private volatile boolean running;
    private PulsarClient client;
    private Consumer<byte[]> consumer;

    public PipelineObjectMaterializationConsumer(
            PipelineObjectMaterializationService materializations,
            ObjectMapper json,
            PulsarProperties properties) {
        this.materializations = materializations;
        this.json = json;
        this.serviceUrl = properties.url().toString();
        this.listenerName = properties.listenerName();
    }

    @Override
    public void start() {
        running = true;
        executor.submit(this::consume);
    }

    private void consume() {
        while (running) {
            Message<byte[]> message = null;
            try {
                if (consumer == null) {
                    connect();
                }
                message = consumer.receive(1, TimeUnit.SECONDS);
                if (message == null) {
                    continue;
                }
                Map<String, Object> event =
                        json.readValue(message.getData(), new TypeReference<>() {});
                switch (String.valueOf(event.get("event_type"))) {
                    case "pipeline.object.complete" -> materializations.complete(event);
                    case "pipeline.object.row" -> materializations.acceptRow(event);
                    default -> throw new IllegalArgumentException(
                            "Unsupported Pipeline object command");
                }
                consumer.acknowledge(message);
            } catch (Exception failure) {
                if (!running || interrupted(failure)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (message != null && consumer != null) {
                    log.warn("Pipeline object command will be redelivered", failure);
                    consumer.negativeAcknowledge(message);
                    continue;
                }
                log.warn("Pipeline object materialization consumer will reconnect", failure);
                closeResources();
                try {
                    TimeUnit.SECONDS.sleep(2);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private boolean interrupted(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private void connect() throws Exception {
        client = PulsarClient.builder()
                .serviceUrl(serviceUrl)
                .listenerName(listenerName)
                .build();
        consumer = client.newConsumer(Schema.BYTES)
                .topic(TOPIC)
                .subscriptionName("ontology-pipeline-object-materialization-v1")
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscriptionType(SubscriptionType.Key_Shared)
                .deadLetterPolicy(DeadLetterPolicy.builder()
                        .maxRedeliverCount(5)
                        .deadLetterTopic(TOPIC + "-dlq")
                        .build())
                .negativeAckRedeliveryDelay(2, TimeUnit.SECONDS)
                .subscribe();
        log.info("Pipeline object materialization consumer connected to {}", TOPIC);
    }

    @Override
    public void stop() {
        running = false;
        closeResources();
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void closeResources() {
        if (consumer != null) {
            try {
                consumer.close();
            } catch (Exception failure) {
                log.warn("Failed to close Pipeline object consumer", failure);
            }
            consumer = null;
        }
        if (client != null) {
            try {
                client.close();
            } catch (Exception failure) {
                log.warn("Failed to close Pipeline object Pulsar client", failure);
            }
            client = null;
        }
    }
}
