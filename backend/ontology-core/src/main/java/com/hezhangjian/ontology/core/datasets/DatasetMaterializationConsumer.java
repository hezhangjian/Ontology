package com.hezhangjian.ontology.core.datasets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SubscriptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
final class DatasetMaterializationConsumer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(DatasetMaterializationConsumer.class);
    private static final String TOPIC = "persistent://platform/ingestion/dataset-events";

    private final DatasetService datasets;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final String pulsarUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("dataset-materialization-consumer").factory());
    private final Map<String, Batch> batches = new LinkedHashMap<>();
    private volatile boolean running;
    private PulsarClient client;
    private Consumer<byte[]> consumer;

    DatasetMaterializationConsumer(DatasetService datasets, JdbcClient jdbc, ObjectMapper json,
                                   @Value("${datasets.pulsar-url:pulsar://pulsar:6650}") String pulsarUrl) {
        this.datasets = datasets;
        this.jdbc = jdbc;
        this.json = json;
        this.pulsarUrl = pulsarUrl;
    }

    @Override
    public void start() {
        running = true;
        executor.submit(this::connectAndConsume);
    }

    private void connectAndConsume() {
        while (running) {
            try {
                if (consumer == null) connect();
                Message<byte[]> message = consumer.receive(1, TimeUnit.SECONDS);
                if (message == null) continue;
                Map<String, Object> event = json.readValue(message.getData(), new TypeReference<>() { });
                String correlationId = String.valueOf(event.get("correlation_id"));
                Batch batch = batches.computeIfAbsent(correlationId, ignored -> new Batch());
                if ("dataset.row".equals(event.get("event_type"))) {
                    UUID eventId = UUID.fromString(String.valueOf(event.get("event_id")));
                    @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                    batch.rows.putIfAbsent(eventId, new RowEvent(eventId, payload,
                            longValue(event.get("ontology_revision"), 1), message.getMessageId().toString()));
                    batch.messages.add(message);
                } else if ("dataset.complete".equals(event.get("event_type"))) {
                    finish(event, batch);
                    for (Message<byte[]> rowMessage : batch.messages) consumer.acknowledge(rowMessage);
                    consumer.acknowledge(message);
                    batches.remove(correlationId);
                } else {
                    consumer.acknowledge(message);
                }
            } catch (Exception cause) {
                log.warn("Dataset materialization consumer will reconnect: {}", cause.getMessage());
                closeResources();
                if (running) try { TimeUnit.SECONDS.sleep(2); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void connect() throws Exception {
        client = PulsarClient.builder().serviceUrl(pulsarUrl).build();
        consumer = client.newConsumer(Schema.BYTES).topic(TOPIC)
                .subscriptionName("ontology-dataset-materializer-v1")
                .subscriptionType(SubscriptionType.Key_Shared)
                .negativeAckRedeliveryDelay(2, TimeUnit.SECONDS).subscribe();
        log.info("Dataset materialization consumer connected to {}", TOPIC);
    }

    private void finish(Map<String, Object> completed, Batch batch) {
        UUID datasetId = UUID.fromString(String.valueOf(completed.get("dataset_id")));
        long expected = longValue(completed.get("row_count"), -1);
        if (expected != batch.rows.size()) {
            throw new IllegalStateException("Dataset batch expected " + expected + " rows but received " + batch.rows.size());
        }
        datasets.finalizeFlinkMaterialization(datasetId,
                batch.rows.values().stream().map(RowEvent::payload).toList());
        long version = 0;
        for (RowEvent row : batch.rows.values()) {
            jdbc.sql("""
                    INSERT INTO control.projection_ledger(event_id,event_type,topic,message_id,ontology_revision,
                      entity_key,entity_version,correlation_id,status,projected_at)
                    VALUES (:eventId,'dataset.row',:topic,:messageId,:revision,:entityKey,:version,:correlation,'PROJECTED',now())
                    ON CONFLICT(event_id) DO UPDATE SET status='PROJECTED',projected_at=now(),updated_at=now()
                    """).param("eventId", row.eventId()).param("topic", TOPIC).param("messageId", row.messageId())
                    .param("revision", row.ontologyRevision()).param("entityKey", "dataset:" + datasetId + ":" + row.eventId())
                    .param("version", ++version).param("correlation", String.valueOf(completed.get("correlation_id"))).update();
        }
    }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
        closeResources();
    }

    private void closeResources() {
        try { if (consumer != null) consumer.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
        consumer = null;
        client = null;
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 100; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }

    private static final class Batch {
        private final Map<UUID, RowEvent> rows = new LinkedHashMap<>();
        private final List<Message<byte[]>> messages = new ArrayList<>();
    }

    private record RowEvent(UUID eventId, Map<String, Object> payload, long ontologyRevision, String messageId) { }
}
