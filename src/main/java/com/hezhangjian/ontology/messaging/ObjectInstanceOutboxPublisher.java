package com.hezhangjian.ontology.messaging;

import com.hezhangjian.ontology.config.PulsarProperties;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ObjectInstanceOutboxPublisher {
    private static final Logger log =
            LoggerFactory.getLogger(ObjectInstanceOutboxPublisher.class);
    private final SqlClientRepository jdbc;
    private final String serviceUrl;
    private final String listenerName;
    private final Map<String, Producer<byte[]>> producers = new ConcurrentHashMap<>();
    private PulsarClient client;

    public ObjectInstanceOutboxPublisher(
            SqlClientRepository jdbc, PulsarProperties properties) {
        this.jdbc = jdbc;
        this.serviceUrl = properties.url().toString();
        this.listenerName = properties.listenerName();
    }

    @Scheduled(fixedDelayString = "${ontology.object-instances.outbox-interval-ms:1000}")
    void publishPending() {
        jdbc.sql("""
                UPDATE control.object_instance_outbox
                SET status='PENDING',next_attempt_at=now()
                WHERE status='PUBLISHING' AND updated_at<now()-interval '1 minute'
                """).update();
        List<OutboxRecord> records = jdbc.sql("""
                WITH claimed AS (
                  SELECT id
                  FROM control.object_instance_outbox
                  WHERE status='PENDING' AND next_attempt_at<=now()
                  ORDER BY created_at
                  LIMIT 100
                  FOR UPDATE SKIP LOCKED
                )
                UPDATE control.object_instance_outbox outbox
                SET status='PUBLISHING',updated_at=now()
                FROM claimed
                WHERE outbox.id=claimed.id
                RETURNING outbox.id,outbox.topic,outbox.message_key,outbox.payload::text
                """).query((row, number) -> new OutboxRecord(
                row.getObject("id", UUID.class),
                row.getString("topic"),
                row.getString("message_key"),
                row.getString("payload"))).list();
        records.forEach(this::publish);
    }

    private void publish(OutboxRecord record) {
        try {
            producer(record.topic())
                    .newMessage()
                    .key(record.messageKey())
                    .value(record.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .sendAsync()
                    .get(15, TimeUnit.SECONDS);
            jdbc.sql("""
                    UPDATE control.object_instance_outbox
                    SET status='PUBLISHED',published_at=now(),last_error=NULL,updated_at=now()
                    WHERE id=:id AND status='PUBLISHING'
                    """).param("id", record.id()).update();
        } catch (Exception failure) {
            String safeError = safeError(failure);
            jdbc.sql("""
                    UPDATE control.object_instance_outbox
                    SET attempts=attempts+1,
                        status=CASE WHEN attempts+1>=20 THEN 'FAILED' ELSE 'PENDING' END,
                        next_attempt_at=now()
                          + LEAST(900, power(2, attempts+1)) * interval '1 second',
                        last_error=:error,updated_at=now()
                    WHERE id=:id AND status='PUBLISHING'
                    """).param("error", safeError).param("id", record.id()).update();
            log.warn("Object instance outbox publication failed for {}", record.id());
        }
    }

    private synchronized Producer<byte[]> producer(String topic) throws Exception {
        Producer<byte[]> existing = producers.get(topic);
        if (existing != null) {
            return existing;
        }
        if (client == null) {
            client = PulsarClient.builder()
                    .serviceUrl(serviceUrl)
                    .listenerName(listenerName)
                    .build();
        }
        Producer<byte[]> created = client.newProducer(Schema.BYTES)
                .topic(topic)
                .enableBatching(true)
                .batchingMaxMessages(500)
                .create();
        producers.put(topic, created);
        return created;
    }

    private String safeError(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) {
            value = "Object instance event publication failed";
        }
        return value.substring(0, Math.min(1000, value.length()));
    }

    private record OutboxRecord(UUID id, String topic, String messageKey, String payload) {}

    @PreDestroy
    synchronized void close() {
        producers.values().forEach(producer -> {
            try {
                producer.close();
            } catch (Exception failure) {
                log.warn("Failed to close object instance producer", failure);
            }
        });
        if (client != null) {
            try {
                client.close();
            } catch (Exception failure) {
                log.warn("Failed to close object instance Pulsar client", failure);
            }
        }
    }
}
