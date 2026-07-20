package com.hezhangjian.ontology.projection.control;

import com.hezhangjian.ontology.contracts.projection.IndexRebuildCommand;
import com.hezhangjian.ontology.projection.model.LedgerEntry;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ControlPlaneRepository {
    private final JdbcTemplate jdbc;

    public ControlPlaneRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean revisionExists(long revision) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM control.ontology_revisions WHERE revision = ?",
                Integer.class,
                revision);
        return count != null && count == 1;
    }

    public Map<String, PropertyContract> objectProperties(long revision, String typeId) {
        List<PropertyContract> properties = jdbc.query(
                """
                SELECT property_id, value_type, required, searchable, sensitive
                FROM control.object_properties
                WHERE revision = ? AND type_id = ?
                """,
                (rs, rowNum) -> new PropertyContract(
                        rs.getString("property_id"),
                        rs.getString("value_type"),
                        rs.getBoolean("required"),
                        rs.getBoolean("searchable"),
                        rs.getBoolean("sensitive")),
                revision,
                typeId);
        return properties.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                PropertyContract::propertyId,
                property -> property));
    }

    public Optional<RelationContract> relation(long revision, String typeId) {
        return jdbc.query(
                        """
                        SELECT source_type_id, target_type_id
                        FROM control.relation_types
                        WHERE revision = ? AND type_id = ? AND active
                        """,
                        (rs, rowNum) -> new RelationContract(
                                rs.getString("source_type_id"), rs.getString("target_type_id")),
                        revision,
                        typeId)
                .stream()
                .findFirst();
    }

    public LedgerEntry register(
            UUID eventId,
            String eventType,
            String topic,
            String messageId,
            long ontologyRevision,
            String entityKey,
            long entityVersion,
            String correlationId) {
        jdbc.update(
                """
                INSERT INTO control.projection_ledger
                    (event_id, event_type, topic, message_id, ontology_revision,
                     entity_key, entity_version, correlation_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId,
                eventType,
                topic,
                messageId,
                ontologyRevision,
                entityKey,
                entityVersion,
                correlationId);
        return get(eventId);
    }

    public LedgerEntry beginAttempt(UUID eventId) {
        jdbc.update(
                """
                UPDATE control.projection_ledger
                SET attempts = attempts + 1, updated_at = now()
                WHERE event_id = ?
                """,
                eventId);
        return get(eventId);
    }

    public boolean newerVersionExists(String entityKey, long entityVersion, UUID eventId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM control.projection_ledger
                WHERE entity_key = ? AND entity_version > ? AND event_id <> ?
                  AND status IN ('GRAPH_APPLIED', 'PROJECTED', 'DEGRADED')
                """,
                Integer.class,
                entityKey,
                entityVersion,
                eventId);
        return count != null && count > 0;
    }

    public void graphApplied(UUID eventId, String graphElementId) {
        jdbc.update(
                """
                UPDATE control.projection_ledger
                SET status = 'GRAPH_APPLIED', graph_element_id = ?, graph_applied_at = now(),
                    last_error_code = NULL, last_error_message = NULL, updated_at = now()
                WHERE event_id = ?
                """,
                graphElementId,
                eventId);
    }

    public void projected(UUID eventId) {
        updateStatus(eventId, "PROJECTED", null, null);
        jdbc.update(
                "UPDATE control.projection_ledger SET projected_at = now() WHERE event_id = ?",
                eventId);
    }

    public void stale(UUID eventId) {
        updateStatus(eventId, "STALE", "STALE_VERSION", "A newer entity version is already projected");
    }

    public void degraded(UUID eventId, String code, String message) {
        updateStatus(eventId, "DEGRADED", code, safe(message));
    }

    public void dlq(UUID eventId, String code, String message) {
        updateStatus(eventId, "DLQ", code, safe(message));
    }

    public void recordFailure(UUID eventId, String code, boolean retryable, int attempt, String message) {
        jdbc.update(
                """
                INSERT INTO control.projection_failures
                    (failure_id, event_id, error_code, retryable, attempt, safe_message)
                VALUES (?, (SELECT event_id FROM control.projection_ledger WHERE event_id = ?), ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                eventId,
                code,
                retryable,
                attempt,
                safe(message));
    }

    public ProjectionOperation registerOperation(
            UUID batchId,
            String idempotencyKey,
            long ontologyRevision,
            String requestedBy,
            String correlationId,
            int editCount) {
        jdbc.update(
                """
                INSERT INTO control.projection_operations
                    (operation_id, idempotency_key, ontology_revision, requested_by,
                     correlation_id, edit_count, status)
                VALUES (?, ?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                batchId,
                idempotencyKey,
                ontologyRevision,
                requestedBy,
                correlationId,
                editCount);
        return jdbc.queryForObject(
                """
                SELECT operation_id, status
                FROM control.projection_operations
                WHERE idempotency_key = ?
                """,
                (rs, rowNum) -> new ProjectionOperation(
                        rs.getObject("operation_id", UUID.class),
                        rs.getString("status")),
                idempotencyKey);
    }

    public void updateOperation(UUID batchId, String status) {
        jdbc.update(
                "UPDATE control.projection_operations SET status = ?, updated_at = now() WHERE operation_id = ?",
                status,
                batchId);
    }

    public RebuildJob startRebuild(IndexRebuildCommand command) {
        jdbc.update(
                """
                INSERT INTO control.index_rebuild_jobs
                    (rebuild_id, requested_by, correlation_id, status, requested_at)
                VALUES (?, ?, ?, 'RECEIVED', ?)
                ON CONFLICT (rebuild_id) DO NOTHING
                """,
                command.rebuildId(),
                command.requestedBy(),
                command.correlationId(),
                Timestamp.from(command.requestedAt()));
        RebuildJob job = rebuildJob(command.rebuildId());
        if (!"SUCCEEDED".equals(job.status())) {
            jdbc.update(
                    """
                    UPDATE control.index_rebuild_jobs
                    SET status = 'RUNNING', started_at = now(), completed_at = NULL, safe_error = NULL
                    WHERE rebuild_id = ?
                    """,
                    command.rebuildId());
            return new RebuildJob(command.rebuildId(), "RUNNING", null, 0);
        }
        return job;
    }

    public void finishRebuild(UUID rebuildId, String index, long count) {
        jdbc.update(
                """
                UPDATE control.index_rebuild_jobs
                SET status = 'SUCCEEDED', target_index = ?, object_count = ?, completed_at = now()
                WHERE rebuild_id = ?
                """,
                index,
                count,
                rebuildId);
    }

    public void failRebuild(UUID rebuildId, String message) {
        jdbc.update(
                """
                UPDATE control.index_rebuild_jobs
                SET status = 'FAILED', safe_error = ?, completed_at = now()
                WHERE rebuild_id = ?
                """,
                safe(message),
                rebuildId);
    }

    private RebuildJob rebuildJob(UUID rebuildId) {
        return jdbc.queryForObject(
                """
                SELECT rebuild_id, status, target_index, object_count
                FROM control.index_rebuild_jobs
                WHERE rebuild_id = ?
                """,
                (rs, rowNum) -> new RebuildJob(
                        rs.getObject("rebuild_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("target_index"),
                        rs.getLong("object_count")),
                rebuildId);
    }

    private LedgerEntry get(UUID eventId) {
        return jdbc.queryForObject(
                """
                SELECT event_id, entity_key, entity_version, status, attempts, graph_element_id
                FROM control.projection_ledger WHERE event_id = ?
                """,
                (rs, rowNum) -> new LedgerEntry(
                        rs.getObject("event_id", UUID.class),
                        rs.getString("entity_key"),
                        rs.getLong("entity_version"),
                        rs.getString("status"),
                        rs.getInt("attempts"),
                        rs.getString("graph_element_id")),
                eventId);
    }

    private void updateStatus(UUID eventId, String status, String code, String message) {
        jdbc.update(
                """
                UPDATE control.projection_ledger
                SET status = ?, last_error_code = ?, last_error_message = ?, updated_at = now()
                WHERE event_id = ?
                """,
                status,
                code,
                message,
                eventId);
    }

    private String safe(String message) {
        String value = message == null ? "Unknown projection failure" : message.replaceAll("[\\r\\n]+", " ");
        return value.substring(0, Math.min(1000, value.length()));
    }

    public record PropertyContract(
            String propertyId,
            String valueType,
            boolean required,
            boolean searchable,
            boolean sensitive) {
    }

    public record RelationContract(String sourceTypeId, String targetTypeId) {
    }

    public record ProjectionOperation(UUID operationId, String status) {
        public boolean projected() {
            return "PROJECTED".equals(status);
        }
    }

    public record RebuildJob(UUID rebuildId, String status, String targetIndex, long objectCount) {
        public boolean succeeded() {
            return "SUCCEEDED".equals(status);
        }
    }
}
