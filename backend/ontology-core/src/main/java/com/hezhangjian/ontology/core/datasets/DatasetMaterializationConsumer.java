package com.hezhangjian.ontology.core.datasets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                if ("dataset.row".equals(event.get("event_type"))) {
                    UUID eventId = UUID.fromString(String.valueOf(event.get("event_id")));
                    @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) event.get("payload");
                    jdbc.sql("""
                            INSERT INTO control.dataset_materialization_rows(
                              correlation_id,event_id,dataset_id,ontology_id,ontology_revision,message_id,payload)
                            VALUES (:correlation,:eventId,:datasetId,:ontologyId,:revision,:messageId,:payload::jsonb)
                            ON CONFLICT(event_id) DO NOTHING
                            """).param("correlation", correlationId).param("eventId", eventId)
                            .param("datasetId", UUID.fromString(String.valueOf(event.get("dataset_id"))))
                            .param("ontologyId", UUID.fromString(String.valueOf(event.get("ontology_id"))))
                            .param("revision", longValue(event.get("ontology_revision"), 1))
                            .param("messageId", message.getMessageId().toString())
                            .param("payload", json.writeValueAsString(payload)).update();
                    consumer.acknowledge(message);
                } else if ("dataset.complete".equals(event.get("event_type"))) {
                    String runStatus = runStatus(event);
                    if (!canDecideMaterialization(runStatus)) {
                        consumer.negativeAcknowledge(message);
                    } else {
                        if (isSuccessfulMaterialization(runStatus)) finish(event);
                        else fail(event, correlationId);
                        consumer.acknowledge(message);
                    }
                } else {
                    consumer.acknowledge(message);
                }
            } catch (Throwable cause) {
                // The consumer is submitted to an ExecutorService, which otherwise retains task
                // failures without logging them and leaves the completion message unacknowledged.
                log.warn("Dataset materialization consumer will reconnect", cause);
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

    private void finish(Map<String, Object> completed) {
        UUID datasetId = UUID.fromString(String.valueOf(completed.get("dataset_id")));
        String correlationId = String.valueOf(completed.get("correlation_id"));
        long expected = longValue(completed.get("row_count"), -1);
        datasets.finalizeFlinkMaterialization(datasetId, correlationId, expected);
        jdbc.sql("""
                INSERT INTO control.projection_ledger(
                  event_id,event_type,topic,message_id,ontology_id,ontology_revision,
                  entity_key,entity_version,correlation_id,status,projected_at)
                SELECT event_id,'dataset.row',:topic,message_id,ontology_id,ontology_revision,
                  'dataset:' || :datasetId || ':' || event_id,
                  row_number() OVER (ORDER BY event_id),correlation_id,'PROJECTED',now()
                FROM control.dataset_materialization_rows
                WHERE dataset_id=:datasetId AND correlation_id=:correlation
                ON CONFLICT(event_id) DO UPDATE
                  SET status='PROJECTED',projected_at=now(),updated_at=now()
                """).param("topic", TOPIC).param("datasetId", datasetId)
                .param("correlation", correlationId).update();
        deleteStaged(datasetId, correlationId);
    }

    private String runStatus(Map<String, Object> completed) {
        UUID runId = UUID.fromString(String.valueOf(completed.get("run_id")));
        return jdbc.sql("SELECT status FROM control.pipeline_runs WHERE id=:id")
                .param("id", runId).query(String.class).optional().orElse("FAILED");
    }

    private boolean canDecideMaterialization(String status) {
        return isSuccessfulMaterialization(status)
                || List.of("CANCELLED", "FAILED", "STOPPED").contains(status);
    }

    private boolean isSuccessfulMaterialization(String status) {
        return List.of("COMPLETED", "DEGRADED", "PROJECTING").contains(status);
    }

    private void fail(Map<String, Object> completed, String correlationId) {
        UUID datasetId = UUID.fromString(String.valueOf(completed.get("dataset_id")));
        jdbc.sql("UPDATE control.datasets SET status='FAILED',row_count=0,schema='[]'::jsonb,updated_at=now() WHERE id=:id")
                .param("id", datasetId).update();
        deleteStaged(datasetId, correlationId);
    }

    private void deleteStaged(UUID datasetId, String correlationId) {
        jdbc.sql("""
                DELETE FROM control.dataset_materialization_rows
                WHERE dataset_id=:datasetId AND correlation_id=:correlation
                """).param("datasetId", datasetId).param("correlation", correlationId).update();
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

}
