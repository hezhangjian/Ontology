package com.hezhangjian.ontology.core.modeling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class ActionMutationPublisher {
    private static final String TOPIC = "persistent://platform/commands/mutation-batches";
    private final ObjectMapper json;
    private final JdbcClient jdbc;
    private final String serviceUrl;
    private PulsarClient client;
    private Producer<byte[]> producer;

    ActionMutationPublisher(ObjectMapper json, JdbcClient jdbc,
                            @Value("${actions.pulsar-url:pulsar://pulsar:6650}") String serviceUrl) {
        this.json = json;
        this.jdbc = jdbc;
        this.serviceUrl = serviceUrl;
    }

    void enqueue(UUID executionId, OntologyMutationBatch batch) {
        try {
            jdbc.sql("""
                    INSERT INTO control.action_mutation_outbox(id,execution_id,payload)
                    VALUES (:id,:executionId,:payload::jsonb)
                    ON CONFLICT(execution_id) DO NOTHING
                    """).param("id", UUID.randomUUID()).param("executionId", executionId)
                    .param("payload", json.writeValueAsString(batch)).update();
        } catch (Exception failure) {
            throw new IllegalStateException("Action mutation could not be queued", failure);
        }
    }

    @Scheduled(fixedDelayString = "${actions.outbox-interval-ms:1000}")
    void publishPending() {
        List<OutboxRecord> records = jdbc.sql("""
                SELECT id,execution_id,payload::text
                FROM control.action_mutation_outbox
                WHERE status='PENDING' AND next_attempt_at<=now()
                ORDER BY created_at
                LIMIT 20
                """).query((row, number) -> new OutboxRecord(
                row.getObject("id", UUID.class), row.getObject("execution_id", UUID.class),
                row.getString("payload"))).list();
        records.forEach(this::publish);
    }

    private void publish(OutboxRecord record) {
        try {
            OntologyMutationBatch batch = json.readValue(record.payload(), OntologyMutationBatch.class);
            send(batch);
            jdbc.sql("""
                    UPDATE control.action_mutation_outbox
                    SET status='PUBLISHED',published_at=now(),last_error=NULL
                    WHERE id=:id AND status='PENDING'
                    """).param("id", record.id()).update();
            jdbc.sql("""
                    UPDATE control.action_executions SET status='PROJECTING'
                    WHERE id=:id AND status='SUBMITTED'
                    """).param("id", record.executionId()).update();
        } catch (Exception failure) {
            String safeError = failure.getMessage() == null
                    ? "Action mutation publication failed" : failure.getMessage();
            jdbc.sql("""
                    UPDATE control.action_mutation_outbox
                    SET attempts=attempts+1,
                        status=CASE WHEN attempts+1>=10 THEN 'FAILED' ELSE 'PENDING' END,
                        next_attempt_at=now() + LEAST(300, power(2, attempts+1)) * interval '1 second',
                        last_error=:error
                    WHERE id=:id
                    """).param("error", safeError.substring(0, Math.min(1000, safeError.length())))
                    .param("id", record.id()).update();
            jdbc.sql("""
                    UPDATE control.action_executions
                    SET status='FAILED',safe_error='Action Mutation 提交失败',completed_at=now()
                    WHERE id=:id AND EXISTS (
                      SELECT 1 FROM control.action_mutation_outbox
                      WHERE execution_id=:id AND status='FAILED')
                    """).param("id", record.executionId()).update();
        }
    }

    private synchronized void send(OntologyMutationBatch batch) throws Exception {
        if (producer == null) {
            client = PulsarClient.builder().serviceUrl(serviceUrl).build();
            producer = client.newProducer(Schema.BYTES).topic(TOPIC).create();
        }
        producer.newMessage().key(batch.idempotencyKey()).value(json.writeValueAsBytes(batch))
                .sendAsync().get(15, TimeUnit.SECONDS);
    }

    private record OutboxRecord(UUID id, UUID executionId, String payload) { }

    @PreDestroy
    synchronized void close() {
        try { if (producer != null) producer.close(); } catch (Exception ignored) { }
        try { if (client != null) client.close(); } catch (Exception ignored) { }
    }
}
