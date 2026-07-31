package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.instance.ObjectInstanceService;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class PipelineObjectMaterializationService {
    private static final int PAGE_SIZE = 500;
    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE =
            new TypeReference<>() {};

    private final ObjectInstanceService instances;
    private final ObjectInstanceRepository repository;
    private final SqlClientRepository jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public PipelineObjectMaterializationService(
            ObjectInstanceService instances,
            ObjectInstanceRepository repository,
            SqlClientRepository jdbc,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.instances = instances;
        this.repository = repository;
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void acceptRow(Map<String, Object> event) {
        Binding binding = binding(event);
        WorkspaceContext.run(binding.ontologyId(), () -> {
            ensureMaterialization(binding);
            if (failed(binding)) {
                return;
            }
            try {
                transactions.executeWithoutResult(ignored ->
                        acceptRowInTransaction(binding, payload(event)));
            } catch (RuntimeException failure) {
                markFailed(binding, safeError(failure));
            }
        });
    }

    private void acceptRowInTransaction(
            Binding binding, Map<String, Object> payload) {
        String validatedId = instances.validateForImport(
                binding.ontologyId(), binding.objectTypeApiName(), payload);
        if (!binding.objectId().equals(validatedId)) {
            throw new ObjectInstanceStoreException(
                    "PIPELINE_OBJECT_ID_MISMATCH",
                    "Pipeline object ID does not match the primary property");
        }
        int inserted = jdbc.sql("""
                INSERT INTO control.pipeline_object_materialization_rows(
                  event_id,run_id,output_node_id,object_id,payload)
                VALUES (:eventId,:runId,:nodeId,:objectId,:payload::jsonb)
                ON CONFLICT(event_id) DO NOTHING
                """).param("eventId", binding.eventId())
                .param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .param("objectId", binding.objectId())
                .param("payload", write(payload))
                .update();
        if (inserted == 0) {
            return;
        }
        if ("STREAMING".equals(binding.pipelineMode())) {
            merge(binding, payload);
            incrementReceived(binding);
            jdbc.sql("""
                    DELETE FROM control.pipeline_object_materialization_rows
                    WHERE event_id=:eventId
                    """).param("eventId", binding.eventId()).update();
        } else {
            incrementReceived(binding);
        }
    }

    public void complete(Map<String, Object> event) {
        Binding binding = binding(event);
        long expectedRows = number(event.get("row_count"));
        WorkspaceContext.run(binding.ontologyId(), () -> {
            ensureMaterialization(binding);
            if ("COMPLETED".equals(status(binding))) {
                return;
            }
            jdbc.sql("""
                    UPDATE control.pipeline_object_materializations
                    SET expected_rows=:expected,updated_at=now()
                    WHERE run_id=:runId AND output_node_id=:nodeId
                    """).param("expected", expectedRows)
                    .param("runId", binding.runId())
                    .param("nodeId", binding.outputNodeId())
                    .update();
            if (failed(binding)) {
                return;
            }
            if ("STREAMING".equals(binding.pipelineMode())) {
                markCompleted(binding, 0);
                deleteStaged(binding);
                return;
            }
            long receivedRows = receivedRows(binding);
            if (receivedRows != expectedRows) {
                markFailed(
                        binding,
                        "Pipeline object row count mismatch: expected "
                                + expectedRows
                                + " but received "
                                + receivedRows);
                return;
            }
            long duplicateIds = jdbc.sql("""
                    SELECT count(*)
                    FROM (
                      SELECT object_id
                      FROM control.pipeline_object_materialization_rows
                      WHERE run_id=:runId AND output_node_id=:nodeId
                      GROUP BY object_id HAVING count(*)>1
                    ) duplicates
                    """).param("runId", binding.runId())
                    .param("nodeId", binding.outputNodeId())
                    .query(Long.class)
                    .single();
            if (duplicateIds > 0) {
                markFailed(binding, "Pipeline object output contains duplicate object IDs");
                return;
            }
            jdbc.sql("""
                    UPDATE control.pipeline_object_materializations
                    SET status='MERGING',updated_at=now()
                    WHERE run_id=:runId AND output_node_id=:nodeId
            """).param("runId", binding.runId())
                    .param("nodeId", binding.outputNodeId())
                    .update();
            try {
                transactions.executeWithoutResult(ignored -> mergeBatch(binding));
            } catch (RuntimeException failure) {
                markFailed(binding, safeError(failure));
            }
        });
    }

    private void mergeBatch(Binding binding) {
        UUID cursor = null;
        while (true) {
            List<StagedObject> page = staged(binding, cursor);
            if (page.isEmpty()) {
                break;
            }
            for (StagedObject row : page) {
                merge(binding.withObject(row.eventId(), row.objectId()), row.payload());
            }
            cursor = page.getLast().eventId();
        }
        long deleted = deleteMissing(binding);
        markCompleted(binding, deleted);
        deleteStaged(binding);
    }

    private void deleteStaged(Binding binding) {
        jdbc.sql("""
                DELETE FROM control.pipeline_object_materialization_rows
                WHERE run_id=:runId AND output_node_id=:nodeId
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .update();
    }

    private void merge(Binding binding, Map<String, Object> payload) {
        var result = instances.mergeBase(
                binding.ontologyId(),
                binding.ontologyApiName(),
                binding.objectTypeApiName(),
                payload,
                "PIPELINE",
                binding.sourceRef(),
                binding.eventId().toString(),
                binding.correlationId());
        String counter = result.eventId() == null
                ? "unchanged_count"
                : result.instance().version() == 1
                        ? "inserted_count"
                        : "updated_count";
        jdbc.sql("""
                UPDATE control.pipeline_object_materializations
                SET %s=%s+1,updated_at=now()
                WHERE run_id=:runId AND output_node_id=:nodeId
                """.formatted(counter, counter))
                .param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .update();
    }

    private long deleteMissing(Binding binding) {
        var schema = repository.schema(binding.ontologyId(), binding.objectTypeApiName());
        long deleted = 0;
        String cursor = null;
        do {
            var page = repository.list(schema, 200, cursor);
            for (var instance : page.items()) {
                if ("PIPELINE".equals(instance.sourceKind())
                        && binding.sourceRef().equals(instance.sourceRef())
                        && !stagedContains(binding, instance.id())) {
                    instances.deleteFromSource(
                            binding.ontologyId(),
                            binding.ontologyApiName(),
                            binding.objectTypeApiName(),
                            instance.id(),
                            instance.version(),
                            "PIPELINE",
                            binding.correlationId());
                    deleted++;
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return deleted;
    }

    private List<StagedObject> staged(Binding binding, UUID cursor) {
        String cursorClause = cursor == null ? "" : " AND event_id>:cursor";
        var query = jdbc.sql("""
                SELECT event_id,object_id,payload::text
                FROM control.pipeline_object_materialization_rows
                WHERE run_id=:runId AND output_node_id=:nodeId%s
                ORDER BY event_id LIMIT :limit
                """.formatted(cursorClause))
                .param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .param("limit", PAGE_SIZE);
        if (cursor != null) {
            query = query.param("cursor", cursor);
        }
        return query.query((row, number) -> new StagedObject(
                row.getObject("event_id", UUID.class),
                row.getString("object_id"),
                read(row.getString("payload")))).list();
    }

    private boolean stagedContains(Binding binding, String objectId) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1
                  FROM control.pipeline_object_materialization_rows
                  WHERE run_id=:runId AND output_node_id=:nodeId
                    AND object_id=:objectId)
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .param("objectId", objectId)
                .query(Boolean.class)
                .single();
    }

    private void ensureMaterialization(Binding binding) {
        jdbc.sql("""
                INSERT INTO control.pipeline_object_materializations(
                  run_id,output_node_id,correlation_id,ontology_id,pipeline_id,
                  object_type_id,object_type_api_name,pipeline_mode)
                SELECT :runId,:nodeId,:correlation,:ontology,:pipeline,
                  :objectType,:objectTypeApi,:mode
                FROM control.pipeline_runs run
                JOIN control.pipelines pipeline ON pipeline.id=run.pipeline_id
                WHERE run.id=:runId
                  AND pipeline.id=:pipeline
                  AND pipeline.ontology_id=:ontology
                  AND pipeline.mode=:mode
                ON CONFLICT(run_id,output_node_id) DO NOTHING
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .param("correlation", binding.correlationId())
                .param("ontology", binding.ontologyId())
                .param("pipeline", binding.pipelineId())
                .param("objectType", binding.objectTypeId())
                .param("objectTypeApi", binding.objectTypeApiName())
                .param("mode", binding.pipelineMode())
                .update();
        boolean valid = jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1
                  FROM control.pipeline_object_materializations
                  WHERE run_id=:runId
                    AND output_node_id=:nodeId
                    AND correlation_id=:correlation
                    AND ontology_id=:ontology
                    AND pipeline_id=:pipeline
                    AND object_type_id=:objectType
                    AND object_type_api_name=:objectTypeApi
                    AND pipeline_mode=:mode)
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .param("correlation", binding.correlationId())
                .param("ontology", binding.ontologyId())
                .param("pipeline", binding.pipelineId())
                .param("objectType", binding.objectTypeId())
                .param("objectTypeApi", binding.objectTypeApiName())
                .param("mode", binding.pipelineMode())
                .query(Boolean.class)
                .single();
        if (!valid) {
            throw new ObjectInstanceStoreException(
                    "PIPELINE_OBJECT_BINDING_MISMATCH",
                    "Pipeline object command does not match its run binding");
        }
    }

    private void incrementReceived(Binding binding) {
        jdbc.sql("""
                UPDATE control.pipeline_object_materializations
                SET received_rows=received_rows+1,updated_at=now()
                WHERE run_id=:runId AND output_node_id=:nodeId
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .update();
    }

    private boolean failed(Binding binding) {
        return "FAILED".equals(status(binding));
    }

    private String status(Binding binding) {
        return jdbc.sql("""
                SELECT status
                FROM control.pipeline_object_materializations
                WHERE run_id=:runId AND output_node_id=:nodeId
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .query(String.class)
                .single();
    }

    private long receivedRows(Binding binding) {
        return jdbc.sql("""
                SELECT received_rows
                FROM control.pipeline_object_materializations
                WHERE run_id=:runId AND output_node_id=:nodeId
                """).param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .query(Long.class)
                .single();
    }

    private void markCompleted(Binding binding, long deleted) {
        jdbc.sql("""
                UPDATE control.pipeline_object_materializations
                SET status='COMPLETED',deleted_count=:deleted,
                    completed_at=now(),updated_at=now()
                WHERE run_id=:runId AND output_node_id=:nodeId
                """).param("deleted", deleted)
                .param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .update();
    }

    private void markFailed(Binding binding, String error) {
        jdbc.sql("""
                UPDATE control.pipeline_object_materializations
                SET status='FAILED',failed_count=failed_count+1,
                    safe_error=:error,completed_at=now(),updated_at=now()
                WHERE run_id=:runId AND output_node_id=:nodeId
                """).param("error", error)
                .param("runId", binding.runId())
                .param("nodeId", binding.outputNodeId())
                .update();
    }

    private Binding binding(Map<String, Object> event) {
        UUID ontologyId = uuid(event, "ontology_id");
        String objectTypePhysicalKey = text(event, "object_type");
        TypeNames names = jdbc.sql("""
                SELECT ontology.api_name AS ontology_api,
                       object_type.id AS object_type_id,
                       object_type.api_name AS object_type_api
                FROM control.ontologies ontology
                JOIN control.ontology_resources object_type
                  ON object_type.ontology_id=ontology.id
                WHERE ontology.id=:ontology
                  AND object_type.kind='OBJECT_TYPE'
                  AND object_type.physical_key=:physicalKey
                """).param("ontology", ontologyId)
                .param("physicalKey", objectTypePhysicalKey)
                .query((row, number) -> new TypeNames(
                        row.getString("ontology_api"),
                        row.getObject("object_type_id", UUID.class),
                        row.getString("object_type_api")))
                .optional()
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "PIPELINE_OBJECT_TYPE_NOT_FOUND",
                        "Pipeline object output references an unknown object type"));
        return new Binding(
                uuid(event, "event_id", UUID.randomUUID()),
                uuid(event, "run_id"),
                text(event, "output_node_id"),
                uuid(event, "run_id"),
                ontologyId,
                names.ontologyApiName(),
                uuid(event, "pipeline_id"),
                names.objectTypeId(),
                names.objectTypeApiName(),
                text(event, "pipeline_mode"),
                String.valueOf(event.getOrDefault("object_id", "")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> event) {
        Object value = event.get("payload");
        if (!(value instanceof Map<?, ?> payload)) {
            throw new ObjectInstanceStoreException(
                    "PIPELINE_OBJECT_PAYLOAD_INVALID",
                    "Pipeline object output payload is missing");
        }
        return (Map<String, Object>) payload;
    }

    private Map<String, Object> read(String value) {
        try {
            return json.readValue(value, PAYLOAD_TYPE);
        } catch (Exception failure) {
            throw new IllegalStateException("Pipeline object staging payload is invalid", failure);
        }
    }

    private String write(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Pipeline object payload cannot be serialized", failure);
        }
    }

    private UUID uuid(Map<String, Object> event, String field) {
        return uuid(event, field, null);
    }

    private UUID uuid(Map<String, Object> event, String field, UUID defaultValue) {
        Object value = event.get(field);
        if (value == null && defaultValue != null) {
            return defaultValue;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (RuntimeException failure) {
            throw new ObjectInstanceStoreException(
                    "PIPELINE_OBJECT_COMMAND_INVALID",
                    "Pipeline object command has an invalid " + field,
                    failure);
        }
    }

    private String text(Map<String, Object> event, String field) {
        String value = String.valueOf(event.getOrDefault(field, "")).trim();
        if (value.isEmpty()) {
            throw new ObjectInstanceStoreException(
                    "PIPELINE_OBJECT_COMMAND_INVALID",
                    "Pipeline object command is missing " + field);
        }
        return value;
    }

    private long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException failure) {
            throw new ObjectInstanceStoreException(
                    "PIPELINE_OBJECT_COMMAND_INVALID",
                    "Pipeline object completion has an invalid row count",
                    failure);
        }
    }

    private String safeError(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) {
            value = "Pipeline object materialization failed";
        }
        return value.substring(0, Math.min(1000, value.length()));
    }

    private record TypeNames(
            String ontologyApiName,
            UUID objectTypeId,
            String objectTypeApiName) {}

    private record StagedObject(
            UUID eventId,
            String objectId,
            Map<String, Object> payload) {}

    private record Binding(
            UUID eventId,
            UUID runId,
            String outputNodeId,
            UUID correlationId,
            UUID ontologyId,
            String ontologyApiName,
            UUID pipelineId,
            UUID objectTypeId,
            String objectTypeApiName,
            String pipelineMode,
            String objectId) {
        private String sourceRef() {
            return pipelineId + ":" + outputNodeId;
        }

        private Binding withObject(UUID nextEventId, String nextObjectId) {
            return new Binding(
                    nextEventId,
                    runId,
                    outputNodeId,
                    correlationId,
                    ontologyId,
                    ontologyApiName,
                    pipelineId,
                    objectTypeId,
                    objectTypeApiName,
                    pipelineMode,
                    nextObjectId);
        }
    }
}
