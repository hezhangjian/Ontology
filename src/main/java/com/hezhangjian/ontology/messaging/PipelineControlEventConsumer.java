package com.hezhangjian.ontology.messaging;

import static com.hezhangjian.ontology.service.PipelineModels.PipelineControlEvent;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.config.PipelineProperties;
import com.hezhangjian.ontology.config.PulsarProperties;
import com.hezhangjian.ontology.service.PipelineRuntimeCoordinator;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import com.hezhangjian.ontology.repo.SqlRepository;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ontology.messaging", name = "consumers-enabled",
        havingValue = "true", matchIfMissing = true)
final class PipelineControlEventConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(PipelineControlEventConsumer.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("pipeline-control-events").factory());
    private final SqlRepository jdbc;
    private final ObjectMapper json;
    private final PipelineProperties properties;
    private final PulsarProperties pulsarProperties;
    private final PipelineRuntimeCoordinator runtime;
    private volatile boolean running;
    private PulsarClient client;
    private Consumer<byte[]> consumer;

    PipelineControlEventConsumer(SqlRepository jdbc, ObjectMapper json, PipelineProperties properties,
                                 PulsarProperties pulsarProperties, PipelineRuntimeCoordinator runtime) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.pulsarProperties = pulsarProperties;
        this.runtime = runtime;
    }

    @Override
    public void start() {
        try {
            client = PulsarClient.builder()
                    .serviceUrl(pulsarProperties.url().toString())
                    .listenerName(pulsarProperties.listenerName())
                    .build();
            consumer = client.newConsumer(Schema.BYTES).topic(properties.controlTopic())
                    .subscriptionName("ontology-pipeline-control-v1")
                    .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                    .subscribe();
            running = true;
            executor.submit(this::consume);
        } catch (Exception cause) {
            closeResources();
            throw new IllegalStateException("Cannot start pipeline control-event consumer", cause);
        }
    }

    private void consume() {
        while (running) {
            try {
                Message<byte[]> message = consumer.receive(1, TimeUnit.SECONDS);
                if (message == null) continue;
                PipelineControlEvent event = json.readValue(message.getData(), PipelineControlEvent.class);
                if (!received(event.eventId())) {
                    runtime.acceptControlEvent(event);
                    record(event.eventId(), message.getMessageId().toString());
                }
                consumer.acknowledge(message);
            } catch (Exception cause) {
                if (running) log.error("Pipeline control-event processing failed", cause);
            }
        }
    }

    private boolean received(UUID eventId) {
        if (eventId == null) return true;
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM control.pipeline_control_event_receipts WHERE event_id=?",
                Integer.class, eventId);
        return count != null && count > 0;
    }

    private void record(UUID eventId, String messageId) {
        if (eventId == null) return;
        try {
            jdbc.update("""
                    INSERT INTO control.pipeline_control_event_receipts(event_id,message_id)
                    VALUES (?,?)
                    """, eventId, messageId);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            // Another consumer instance completed the same idempotent event first.
        }
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
        try {
            if (consumer != null) consumer.close();
        } catch (Exception closeFailure) {
            log.warn("Failed to close pipeline control consumer", closeFailure);
        }
        try {
            if (client != null) client.close();
        } catch (Exception closeFailure) {
            log.warn("Failed to close pipeline control Pulsar client", closeFailure);
        }
        consumer = null;
        client = null;
    }
}
