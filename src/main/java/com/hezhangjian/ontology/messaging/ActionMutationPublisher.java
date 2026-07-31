package com.hezhangjian.ontology.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.config.PulsarProperties;
import com.hezhangjian.ontology.contracts.projection.MutationEdit;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import com.hezhangjian.ontology.instance.ObjectInstanceService;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import com.hezhangjian.ontology.security.WorkspaceContext;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class ActionMutationPublisher {
    private static final Logger log = LoggerFactory.getLogger(ActionMutationPublisher.class);
    private static final String TOPIC = "persistent://platform/commands/mutation-batches";
    private final ObjectMapper json;
    private final SqlClientRepository jdbc;
    private final ObjectInstanceRepository objectRepository;
    private final ObjectInstanceService objectInstances;
    private final String serviceUrl;
    private final String listenerName;
    private PulsarClient client;
    private Producer<byte[]> producer;

    public ActionMutationPublisher(
            ObjectMapper json,
            SqlClientRepository jdbc,
            ObjectInstanceRepository objectRepository,
            ObjectInstanceService objectInstances,
            PulsarProperties properties) {
        this.json = json;
        this.jdbc = jdbc;
        this.objectRepository = objectRepository;
        this.objectInstances = objectInstances;
        this.serviceUrl = properties.url().toString();
        this.listenerName = properties.listenerName();
    }

    public void enqueue(UUID executionId, OntologyMutationBatch batch) {
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

    @Scheduled(fixedDelayString = "${ontology.actions.outbox-interval-ms:1000}")
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
            List<MutationEdit> remaining = WorkspaceContext.call(
                    batch.ontologyId(), () -> applyObjectEdits(batch));
            if (!remaining.isEmpty()) {
                send(new OntologyMutationBatch(
                        batch.batchId(),
                        batch.ontologyId(),
                        batch.actionTypeId(),
                        batch.previewTokenId(),
                        batch.idempotencyKey(),
                        batch.occurredAt(),
                        batch.correlationId(),
                        remaining));
            }
            jdbc.sql("""
                    UPDATE control.action_mutation_outbox
                    SET status='PUBLISHED',published_at=now(),last_error=NULL
                    WHERE id=:id AND status='PENDING'
                    """).param("id", record.id()).update();
            jdbc.sql("""
                    UPDATE control.action_executions
                    SET status=:status,
                        completed_at=CASE WHEN :status='SUCCEEDED' THEN now() ELSE completed_at END
                    WHERE id=:id AND status='SUBMITTED'
                    """).param("status", remaining.isEmpty() ? "SUCCEEDED" : "PROJECTING")
                    .param("id", record.executionId()).update();
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

    private List<MutationEdit> applyObjectEdits(OntologyMutationBatch batch) {
        String ontologyApiName = jdbc.sql("""
                SELECT api_name FROM control.ontologies WHERE id=:id
                """).param("id", batch.ontologyId()).query(String.class).single();
        java.util.ArrayList<MutationEdit> remaining = new java.util.ArrayList<>();
        int index = 0;
        for (MutationEdit edit : batch.edits()) {
            if (edit.operation() == null || !edit.operation().startsWith("object.")) {
                remaining.add(edit);
                continue;
            }
            String objectTypeApiName = jdbc.sql("""
                    SELECT api_name FROM control.ontology_resources
                    WHERE ontology_id=:ontology AND kind='OBJECT_TYPE'
                      AND physical_key=:physicalKey
                    """).param("ontology", batch.ontologyId())
                    .param("physicalKey", edit.objectTypeId())
                    .query(String.class)
                    .single();
            var schema = objectRepository.schema(batch.ontologyId(), objectTypeApiName);
            Map<String, Object> properties = new LinkedHashMap<>();
            if (edit.properties() != null && edit.properties().isObject()) {
                edit.properties().fields().forEachRemaining(entry ->
                        schema.properties().stream()
                                .filter(property ->
                                        property.physicalKey().equals(entry.getKey()))
                                .findFirst()
                                .ifPresent(property -> properties.put(
                                        property.apiName(),
                                        json.convertValue(entry.getValue(), Object.class))));
            }
            switch (edit.operation()) {
                case "object.create" -> objectInstances.create(
                        batch.ontologyId(),
                        ontologyApiName,
                        objectTypeApiName,
                        properties,
                        batch.idempotencyKey() + ":" + index);
                case "object.update" -> {
                    var current = objectInstances.get(
                            batch.ontologyId(), objectTypeApiName, edit.objectId());
                    objectInstances.update(
                            batch.ontologyId(),
                            ontologyApiName,
                            objectTypeApiName,
                            edit.objectId(),
                            current.version(),
                            properties);
                }
                case "object.delete" -> {
                    var current = objectInstances.get(
                            batch.ontologyId(), objectTypeApiName, edit.objectId());
                    objectInstances.delete(
                            batch.ontologyId(),
                            ontologyApiName,
                            objectTypeApiName,
                            edit.objectId(),
                            current.version());
                }
                case "object.clear_overrides" -> objectInstances.clearOverrides(
                        batch.ontologyId(),
                        ontologyApiName,
                        objectTypeApiName,
                        edit.objectId());
                default -> throw new IllegalArgumentException(
                        "Unsupported action object mutation: " + edit.operation());
            }
            index++;
        }
        return List.copyOf(remaining);
    }

    private synchronized void send(OntologyMutationBatch batch) throws Exception {
        if (producer == null) {
            client = PulsarClient.builder()
                    .serviceUrl(serviceUrl)
                    .listenerName(listenerName)
                    .build();
            producer = client.newProducer(Schema.BYTES).topic(TOPIC).create();
        }
        producer.newMessage().key(batch.idempotencyKey()).value(json.writeValueAsBytes(batch))
                .sendAsync().get(15, TimeUnit.SECONDS);
    }

    private record OutboxRecord(UUID id, UUID executionId, String payload) { }

    @PreDestroy
    synchronized void close() {
        try {
            if (producer != null) producer.close();
        } catch (Exception closeFailure) {
            log.warn("Failed to close Action Mutation producer", closeFailure);
        }
        try {
            if (client != null) client.close();
        } catch (Exception closeFailure) {
            log.warn("Failed to close Action Mutation Pulsar client", closeFailure);
        }
    }
}
