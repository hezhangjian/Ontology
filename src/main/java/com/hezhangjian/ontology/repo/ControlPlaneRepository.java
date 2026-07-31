package com.hezhangjian.ontology.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.IndexRebuildCommand;
import com.hezhangjian.ontology.projection.model.LedgerEntry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ControlPlaneRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ControlPlaneRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, PropertyContract> objectProperties(UUID ontologyId, String typeId) {
        List<PropertyContract> properties = jdbc.query(
                """
                SELECT property_id, value_type, required, searchable, sensitive
                FROM control.object_properties
                WHERE ontology_id = ? AND type_id = ?
                """,
                (rs, rowNum) -> new PropertyContract(
                        rs.getString("property_id"),
                        rs.getString("value_type"),
                        rs.getBoolean("required"),
                        rs.getBoolean("searchable"),
                        rs.getBoolean("sensitive")),
                ontologyId,
                typeId);
        return properties.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                PropertyContract::propertyId,
                property -> property));
    }

    public List<ObjectSchema> objectSchemas(UUID ontologyId) {
        return jdbc.query(
                """
                SELECT type_id
                FROM control.object_types
                WHERE ontology_id = ? AND active
                ORDER BY type_id
                """,
                (rs, rowNum) -> new ObjectSchema(
                        rs.getString("type_id"),
                        objectProperties(ontologyId, rs.getString("type_id"))),
                ontologyId);
    }

    public Optional<ObjectSchema> objectSchema(UUID ontologyId, String typeId) {
        return objectSchemas(ontologyId).stream()
                .filter(schema -> schema.typeId().equals(typeId))
                .findFirst();
    }

    public Optional<RelationContract> relation(UUID ontologyId, String typeId) {
        return jdbc.query(
                        """
                        SELECT source_type_id, target_type_id, source_mode, source_property_id
                        FROM control.relation_types
                        WHERE ontology_id = ? AND type_id = ? AND active
                        """,
                        (rs, rowNum) -> new RelationContract(
                                rs.getString("source_type_id"), rs.getString("target_type_id"),
                                rs.getString("source_mode"), rs.getString("source_property_id")),
                        ontologyId,
                        typeId)
                .stream()
                .findFirst();
    }

    public List<RelationSchema> relationSchemas(UUID ontologyId) {
        return jdbc.query(
                """
                SELECT type_id, source_type_id, target_type_id, source_mode, source_property_id
                FROM control.relation_types
                WHERE ontology_id = ? AND active
                ORDER BY type_id
                """,
                (rs, rowNum) -> new RelationSchema(
                        rs.getString("type_id"),
                        rs.getString("source_type_id"),
                        rs.getString("target_type_id"),
                        rs.getString("source_mode"),
                        rs.getString("source_property_id")),
                ontologyId);
    }

    public List<ForeignKeyContract> foreignKeys(UUID ontologyId, String sourceTypeId) {
        return jdbc.query("""
                SELECT type_id,source_type_id,target_type_id,source_property_id
                FROM control.relation_types
                WHERE ontology_id=? AND source_type_id=? AND source_mode='FOREIGN_KEY'
                  AND active AND source_property_id IS NOT NULL
                ORDER BY type_id
                """, (row, number) -> new ForeignKeyContract(
                row.getString("type_id"), row.getString("source_type_id"),
                row.getString("target_type_id"), row.getString("source_property_id")),
                ontologyId, sourceTypeId);
    }

    public List<ForeignKeyContract> foreignKeys(UUID ontologyId) {
        return jdbc.query("""
                SELECT type_id,source_type_id,target_type_id,source_property_id
                FROM control.relation_types
                WHERE ontology_id=? AND source_mode='FOREIGN_KEY'
                  AND active AND source_property_id IS NOT NULL
                ORDER BY type_id
                """, (row, number) -> new ForeignKeyContract(
                row.getString("type_id"), row.getString("source_type_id"),
                row.getString("target_type_id"), row.getString("source_property_id")),
                ontologyId);
    }

    public List<UUID> ontologyIds() {
        return jdbc.query("SELECT id FROM control.ontologies ORDER BY id",
                (row, number) -> row.getObject("id", UUID.class));
    }

    public List<ForeignKeyState> foreignKeyStatesForSource(
            UUID ontologyId, String sourceType, String sourceId) {
        return jdbc.query("""
                SELECT * FROM control.projection_fk_relations
                WHERE ontology_id=? AND source_object_type=? AND source_object_id=?
                ORDER BY relation_type
                """, (row, number) -> foreignKeyState(row), ontologyId, sourceType, sourceId);
    }

    public List<ForeignKeyState> pendingForeignKeysForTarget(
            UUID ontologyId, String targetType, String targetId) {
        return jdbc.query("""
                SELECT * FROM control.projection_fk_relations
                WHERE ontology_id=? AND target_object_type=? AND target_object_id=?
                  AND status='PENDING'
                ORDER BY relation_type,source_object_id
                """, (row, number) -> foreignKeyState(row), ontologyId, targetType, targetId);
    }

    public List<ForeignKeyState> foreignKeyStatesForTarget(
            UUID ontologyId, String targetType, String targetId) {
        return jdbc.query("""
                SELECT * FROM control.projection_fk_relations
                WHERE ontology_id=? AND target_object_type=? AND target_object_id=?
                ORDER BY relation_type,source_object_id
                """, (row, number) -> foreignKeyState(row), ontologyId, targetType, targetId);
    }

    public List<ForeignKeyState> foreignKeyStatesForOntology(UUID ontologyId) {
        return jdbc.query("""
                SELECT * FROM control.projection_fk_relations
                WHERE ontology_id=?
                ORDER BY relation_type,source_object_id
                """, (row, number) -> foreignKeyState(row), ontologyId);
    }

    public void saveForeignKeyState(ForeignKeyState state) {
        jdbc.update("""
                INSERT INTO control.projection_fk_relations(
                  ontology_id,relation_type,source_object_type,source_object_id,
                  target_object_type,target_object_id,relation_id,status,last_error)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (ontology_id,relation_type,source_object_type,source_object_id)
                DO UPDATE SET target_object_type=excluded.target_object_type,
                  target_object_id=excluded.target_object_id,relation_id=excluded.relation_id,
                  status=excluded.status,
                  last_error=excluded.last_error,updated_at=now()
                """, state.ontologyId(), state.relationType(), state.sourceObjectType(),
                state.sourceObjectId(), state.targetObjectType(), state.targetObjectId(),
                state.relationId(), state.status(), state.lastError());
    }

    public void deleteForeignKeyState(ForeignKeyState state) {
        jdbc.update("""
                DELETE FROM control.projection_fk_relations
                WHERE ontology_id=? AND relation_type=? AND source_object_type=? AND source_object_id=?
                """, state.ontologyId(), state.relationType(),
                state.sourceObjectType(), state.sourceObjectId());
    }

    public void refreshForeignKeyHealth(UUID ontologyId) {
        Long pending = jdbc.queryForObject("""
                SELECT count(*) FROM control.projection_fk_relations
                WHERE ontology_id=? AND status='PENDING'
                """, Long.class, ontologyId);
        String issueKey = "projection:unresolved-foreign-keys";
        if (pending == null || pending == 0) {
            jdbc.update("""
                    UPDATE control.ontology_health_issues
                    SET status='RESOLVED',last_seen_at=now()
                    WHERE ontology_id=? AND issue_key=?
                    """, ontologyId, issueKey);
            return;
        }
        jdbc.update("""
                INSERT INTO control.ontology_health_issues(
                  id,ontology_id,issue_key,severity,category,title,evidence,
                  recommendation,owner_name)
                VALUES (?,?,?,'WARNING','PROJECTION','存在未解析的外键关系',?,
                  '确认目标对象已成功投影；目标到达后系统会自动补边','Platform Admin')
                ON CONFLICT (ontology_id,issue_key) DO UPDATE
                SET status='OPEN',evidence=excluded.evidence,last_seen_at=now()
                """, UUID.randomUUID(), ontologyId, issueKey,
                "当前有 " + pending + " 条外键关系等待目标对象");
    }

    private ForeignKeyState foreignKeyState(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ForeignKeyState(row.getObject("ontology_id", UUID.class),
                row.getString("relation_type"), row.getString("source_object_type"),
                row.getString("source_object_id"), row.getString("target_object_type"),
                row.getString("target_object_id"), row.getString("relation_id"),
                row.getString("status"),
                row.getString("last_error"));
    }

    private String entityKey(UUID ontologyId, String kind, String type, String id) {
        return ontologyId + ":" + kind + ":" + type + ":" + id;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored projection payload is invalid", failure);
        }
    }

    @Transactional
    public LedgerEntry register(
            UUID eventId,
            String eventType,
            String topic,
            String messageId,
            UUID ontologyId,
            String entityKey,
            String correlationId) {
        List<LedgerEntry> existing = jdbc.query(
                """
                SELECT event_id, entity_key, projection_sequence, status, attempts, graph_element_id
                FROM control.projection_ledger WHERE event_id = ?
                """,
                (rs, rowNum) -> ledger(rs),
                eventId);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        Long projectionSequence = jdbc.queryForObject(
                """
                INSERT INTO control.relation_projection_state
                    (ontology_id, entity_key, projection_sequence)
                VALUES (?, ?, 1)
                ON CONFLICT (ontology_id, entity_key)
                DO UPDATE SET projection_sequence =
                                  control.relation_projection_state.projection_sequence + 1,
                              updated_at = now()
                RETURNING projection_sequence
                """,
                Long.class,
                ontologyId,
                entityKey);
        jdbc.update(
                """
                INSERT INTO control.projection_ledger
                    (event_id, event_type, topic, message_id, ontology_id,
                     entity_key, projection_sequence, correlation_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (event_id) DO NOTHING
                """,
                eventId,
                eventType,
                topic,
                messageId,
                ontologyId,
                entityKey,
                projectionSequence,
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

    public boolean newerSequenceExists(String entityKey, long projectionSequence, UUID eventId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT count(*) FROM control.projection_ledger
                WHERE entity_key = ? AND projection_sequence > ? AND event_id <> ?
                  AND status IN ('GRAPH_APPLIED', 'PROJECTED', 'DEGRADED')
                """,
                Integer.class,
                entityKey,
                projectionSequence,
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

    public void graphAppliedBatch(List<GraphUpdate> updates) {
        jdbc.batchUpdate(
                """
                UPDATE control.projection_ledger
                SET status = 'GRAPH_APPLIED', graph_element_id = ?, graph_applied_at = now(),
                    last_error_code = NULL, last_error_message = NULL, updated_at = now()
                WHERE event_id = ?
                """,
                updates.stream()
                        .map(update -> new Object[] { update.graphElementId(), update.eventId() })
                        .toList());
    }

    public void projected(UUID eventId) {
        jdbc.update(
                """
                UPDATE control.projection_ledger
                SET status = 'PROJECTED', projected_at = now(), last_error_code = NULL,
                    last_error_message = NULL, updated_at = now()
                WHERE event_id = ?
                """,
                eventId);
    }

    public void projectedBatch(List<UUID> eventIds) {
        List<Object[]> arguments = new ArrayList<>();
        eventIds.forEach(eventId -> arguments.add(new Object[] { eventId }));
        jdbc.batchUpdate(
                """
                UPDATE control.projection_ledger
                SET status = 'PROJECTED', projected_at = now(), last_error_code = NULL,
                    last_error_message = NULL, updated_at = now()
                WHERE event_id = ?
                """,
                arguments);
    }

    public void stale(UUID eventId) {
        updateStatus(eventId, "STALE", "STALE_SEQUENCE", "A newer projection sequence is already projected");
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
            UUID ontologyId,
            String correlationId,
            int editCount) {
        jdbc.update(
                """
                INSERT INTO control.projection_operations
                    (operation_id, idempotency_key, ontology_id,
                     correlation_id, edit_count, status)
                VALUES (?, ?, ?, ?, ?, 'RECEIVED')
                ON CONFLICT (idempotency_key) DO NOTHING
                """,
                batchId,
                idempotencyKey,
                ontologyId,
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
                    (rebuild_id, ontology_id, requested_by, correlation_id, status, requested_at)
                VALUES (?, ?, ?, ?, 'RECEIVED', ?)
                ON CONFLICT (rebuild_id) DO NOTHING
                """,
                command.rebuildId(),
                command.ontologyId(),
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
                SELECT event_id, entity_key, projection_sequence, status, attempts, graph_element_id
                FROM control.projection_ledger WHERE event_id = ?
                """,
                (rs, rowNum) -> ledger(rs),
                eventId);
    }

    private LedgerEntry ledger(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LedgerEntry(
                rs.getObject("event_id", UUID.class),
                rs.getString("entity_key"),
                rs.getLong("projection_sequence"),
                rs.getString("status"),
                rs.getInt("attempts"),
                rs.getString("graph_element_id"));
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

    public record ObjectSchema(String typeId, Map<String, PropertyContract> properties) {
    }

    public record RelationContract(
            String sourceTypeId, String targetTypeId, String sourceMode, String sourcePropertyId) {
    }

    public record RelationSchema(
            String typeId,
            String sourceTypeId,
            String targetTypeId,
            String sourceMode,
            String sourcePropertyId) {
    }

    public record ForeignKeyContract(
            String relationType, String sourceType, String targetType, String sourcePropertyId) {
    }

    public record ForeignKeyState(
            UUID ontologyId,
            String relationType,
            String sourceObjectType,
            String sourceObjectId,
            String targetObjectType,
            String targetObjectId,
            String relationId,
            String status,
            String lastError) {
    }

    public record GraphUpdate(UUID eventId, String graphElementId) {
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
