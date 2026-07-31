package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.instance.ObjectInstanceEvent;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.model.ObjectInstanceReconciliationJob;
import com.hezhangjian.ontology.model.ObjectInstanceReconciliationDifference;
import com.hezhangjian.ontology.model.ObjectInstanceReconciliationResult;
import com.hezhangjian.ontology.projection.ObjectInstanceProjectionProcessor;
import com.hezhangjian.ontology.projection.ObjectInstanceProjectionProcessor.Target;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient.GraphObject;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectInstanceReconciliationService {
    private final OntologyLookupService catalogs;
    private final ObjectInstanceRepository repository;
    private final ObjectInstanceProjectionProcessor projections;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;
    private final EventContractValidator validator;
    private final SqlClientRepository jdbc;
    private final ObjectMapper json;

    public ObjectInstanceReconciliationService(
            OntologyLookupService catalogs,
            ObjectInstanceRepository repository,
            ObjectInstanceProjectionProcessor projections,
            HugeGraphProjectionClient graph,
            OpenSearchProjectionClient search,
            EventContractValidator validator,
            SqlClientRepository jdbc,
            ObjectMapper json) {
        this.catalogs = catalogs;
        this.repository = repository;
        this.projections = projections;
        this.graph = graph;
        this.search = search;
        this.validator = validator;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public ObjectInstanceReconciliationJob create(
            String ontologyApiName, String objectTypeApiName, boolean repair) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        return WorkspaceContext.call(ontologyId, () -> {
            var schema = repository.schema(ontologyId, objectTypeApiName);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO control.object_instance_reconciliation_jobs(
                      id,ontology_id,object_type_id,repair)
                    VALUES (:id,:ontology,:objectType,:repair)
                    """).param("id", id)
                    .param("ontology", ontologyId)
                    .param("objectType", schema.objectTypeId())
                    .param("repair", repair)
                    .update();
            return job(id);
        });
    }

    public ObjectInstanceReconciliationResult get(
            String ontologyApiName, String objectTypeApiName, UUID jobId) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        return WorkspaceContext.call(ontologyId, () -> {
            UUID objectTypeId =
                    repository.schema(ontologyId, objectTypeApiName).objectTypeId();
            ObjectInstanceReconciliationJob current = jdbc.sql("""
                    SELECT * FROM control.object_instance_reconciliation_jobs
                    WHERE id=:id AND ontology_id=:ontology AND object_type_id=:objectType
                    """).param("id", jobId)
                    .param("ontology", ontologyId)
                    .param("objectType", objectTypeId)
                    .query((row, number) -> mapJob(row))
                    .optional()
                    .orElseThrow(() -> new ObjectInstanceStoreException(
                            "RECONCILIATION_NOT_FOUND",
                            "Reconciliation job does not exist"));
            List<ObjectInstanceReconciliationDifference> differences = jdbc.sql("""
                    SELECT target,object_id,difference_kind,authoritative_version,
                           projected_version,repair_status,safe_error
                    FROM control.object_instance_reconciliation_differences
                    WHERE job_id=:job
                    ORDER BY target,difference_kind,object_id
                    """).param("job", jobId)
                    .query((row, number) -> new ObjectInstanceReconciliationDifference()
                            .target(ObjectInstanceReconciliationDifference.TargetEnum.fromValue(
                                    row.getString("target")))
                            .objectId(row.getString("object_id"))
                            .kind(ObjectInstanceReconciliationDifference.KindEnum.fromValue(
                                    row.getString("difference_kind")))
                            .authoritativeVersion(
                                    row.getObject("authoritative_version", Long.class))
                            .projectedVersion(row.getObject("projected_version", Long.class))
                            .repairStatus(row.getString("repair_status"))
                            .safeError(row.getString("safe_error")))
                    .list();
            return new ObjectInstanceReconciliationResult()
                    .job(current)
                    .differences(differences);
        });
    }

    @Scheduled(
            fixedDelayString =
                    "${ontology.object-instances.reconciliation-interval-ms:300000}",
            initialDelay = 30_000)
    void enqueueScheduled() {
        jdbc.sql("""
                INSERT INTO control.object_instance_reconciliation_jobs(
                  id,ontology_id,object_type_id,repair)
                SELECT gen_random_uuid(),r.ontology_id,r.object_type_id,true
                FROM control.object_instance_table_registry r
                WHERE r.status='READY' AND NOT EXISTS (
                  SELECT 1 FROM control.object_instance_reconciliation_jobs j
                  WHERE j.ontology_id=r.ontology_id AND j.object_type_id=r.object_type_id
                    AND j.status IN ('QUEUED','RUNNING'))
                """).update();
    }

    @Scheduled(
            fixedDelayString =
                    "${ontology.object-instances.degraded-reconciliation-interval-ms:10000}",
            initialDelay = 10_000)
    void enqueueDegraded() {
        jdbc.sql("""
                INSERT INTO control.object_instance_reconciliation_jobs(
                  id,ontology_id,object_type_id,repair)
                SELECT gen_random_uuid(),p.ontology_id,p.object_type_id,true
                FROM control.object_instance_projection_state p
                JOIN control.object_instance_table_registry r
                  ON r.ontology_id=p.ontology_id AND r.object_type_id=p.object_type_id
                WHERE p.status IN ('DEGRADED','DLQ') AND r.status='READY'
                  AND NOT EXISTS (
                    SELECT 1 FROM control.object_instance_reconciliation_jobs j
                    WHERE j.ontology_id=p.ontology_id AND j.object_type_id=p.object_type_id
                      AND j.status IN ('QUEUED','RUNNING'))
                GROUP BY p.ontology_id,p.object_type_id
                """).update();
    }

    @Scheduled(fixedDelay = 2_000, initialDelay = 10_000)
    void processQueued() {
        List<Work> work = jdbc.sql("""
                SELECT j.id,j.ontology_id,j.object_type_id,j.repair,
                       o.api_name ontology_api,r.api_name object_type_api
                FROM control.object_instance_reconciliation_jobs j
                JOIN control.ontologies o ON o.id=j.ontology_id
                JOIN control.ontology_resources r ON r.id=j.object_type_id
                WHERE j.status='QUEUED'
                ORDER BY j.created_at LIMIT 1
                """).query((row, number) -> new Work(
                row.getObject("id", UUID.class),
                row.getObject("ontology_id", UUID.class),
                row.getObject("object_type_id", UUID.class),
                row.getBoolean("repair"),
                row.getString("ontology_api"),
                row.getString("object_type_api"))).list();
        work.forEach(this::process);
    }

    private void process(Work work) {
        if (jdbc.sql("""
                UPDATE control.object_instance_reconciliation_jobs
                SET status='RUNNING',started_at=now()
                WHERE id=:id AND status='QUEUED'
                """).param("id", work.id()).update() == 0) {
            return;
        }
        WorkspaceContext.run(work.ontologyId(), () -> {
            long missing = 0;
            long stale = 0;
            long extra = 0;
            long repaired = 0;
            try {
                var schema = repository.schema(work.ontologyId(), work.objectTypeApiName());
                Map<String, com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance>
                        authoritative = authoritative(schema);
                Map<String, GraphObject> graphObjects = graph.listObjects().stream()
                        .filter(value -> work.ontologyId().toString().equals(value.ontologyId()))
                        .filter(value -> schema.objectTypePhysicalKey().equals(value.objectType()))
                        .collect(java.util.stream.Collectors.toMap(
                                GraphObject::objectId, value -> value, (left, right) -> left));
                Map<String, GraphObject> searchObjects = search
                        .currentObjects(work.ontologyId(), schema.objectTypePhysicalKey())
                        .stream()
                        .collect(java.util.stream.Collectors.toMap(
                                GraphObject::objectId, value -> value, (left, right) -> left));
                for (var entry : authoritative.entrySet()) {
                    ObjectNode payload = effective(entry.getValue());
                    ObjectInstanceEvent event =
                            event(work, schema, entry.getValue(), payload, false);
                    Difference graphDifference =
                            difference(entry.getValue(), payload, graphObjects.get(entry.getKey()));
                    if (graphDifference != null) {
                        if (graphDifference == Difference.MISSING) missing++; else stale++;
                        record(work.id(), Target.HUGEGRAPH, entry.getKey(), graphDifference,
                                entry.getValue().version(),
                                graphObjects.get(entry.getKey()) == null
                                        ? null
                                        : graphObjects.get(entry.getKey()).projectionSequence());
                        if (work.repair()) {
                            projections.repair(Target.HUGEGRAPH, event);
                            repaired(work.id(), Target.HUGEGRAPH, entry.getKey(), graphDifference);
                            repaired++;
                        }
                    } else if (work.repair()) {
                        projections.process(Target.HUGEGRAPH, event);
                    }
                    JsonNode searchable = validator.filterSearchable(
                            work.ontologyId(), schema.objectTypePhysicalKey(), payload);
                    Difference searchDifference = difference(
                            entry.getValue(),
                            searchable,
                            searchObjects.get(entry.getKey()));
                    if (searchDifference != null) {
                        if (searchDifference == Difference.MISSING) missing++; else stale++;
                        record(work.id(), Target.OPENSEARCH, entry.getKey(), searchDifference,
                                entry.getValue().version(),
                                searchObjects.get(entry.getKey()) == null
                                        ? null
                                        : searchObjects.get(entry.getKey()).projectionSequence());
                        if (work.repair()) {
                            projections.repair(Target.OPENSEARCH, event);
                            repaired(work.id(), Target.OPENSEARCH, entry.getKey(), searchDifference);
                            repaired++;
                        }
                    } else if (work.repair()) {
                        projections.process(Target.OPENSEARCH, event);
                    }
                }
                for (Target target : Target.values()) {
                    Map<String, GraphObject> actual =
                            target == Target.HUGEGRAPH ? graphObjects : searchObjects;
                    for (GraphObject value : actual.values()) {
                        if (!authoritative.containsKey(value.objectId())) {
                            extra++;
                            record(work.id(), target, value.objectId(), Difference.EXTRA,
                                    null, value.projectionSequence());
                            if (work.repair()) {
                                projections.repair(target, deleteEvent(
                                        work, schema, value.objectId(),
                                        value.projectionSequence() + 1));
                                repaired(work.id(), target, value.objectId(), Difference.EXTRA);
                                repaired++;
                            }
                        }
                    }
                }
                complete(work.id(), "COMPLETED", missing, stale, extra, repaired, null);
            } catch (RuntimeException failure) {
                complete(work.id(), "FAILED", missing, stale, extra, repaired, safeError(failure));
            }
        });
    }

    private Map<String, com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance>
            authoritative(
                    com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema schema) {
        Map<String, com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance> result =
                new LinkedHashMap<>();
        String cursor = null;
        do {
            var page = repository.list(schema, 200, cursor);
            page.items().forEach(value -> result.put(value.id(), value));
            cursor = page.nextCursor();
        } while (cursor != null);
        return result;
    }

    private Difference difference(
            com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance authoritative,
            JsonNode payload,
            GraphObject actual) {
        if (actual == null) {
            return Difference.MISSING;
        }
        if (actual.projectionSequence() != authoritative.version()
                || !payload.equals(actual.payload())) {
            return Difference.STALE;
        }
        return null;
    }

    private ObjectNode effective(
            com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance value) {
        ObjectNode result = value.basePayload().isObject()
                ? ((ObjectNode) value.basePayload()).deepCopy()
                : json.createObjectNode();
        if (value.overridePayload().isObject()) {
            value.overridePayload().fields()
                    .forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private ObjectInstanceEvent event(
            Work work,
            com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema schema,
            com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance value,
            JsonNode payload,
            boolean deleted) {
        return new ObjectInstanceEvent(
                UUID.randomUUID(),
                deleted ? "delete" : "update",
                1,
                work.ontologyId(),
                work.ontologyApiName(),
                work.objectTypeId(),
                work.objectTypeApiName(),
                schema.objectTypePhysicalKey(),
                value.id(),
                value.version(),
                value.title(),
                payload,
                Instant.now(),
                UUID.randomUUID(),
                "RECONCILIATION",
                deleted);
    }

    private ObjectInstanceEvent deleteEvent(
            Work work,
            com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema schema,
            String objectId,
            long version) {
        return new ObjectInstanceEvent(
                UUID.randomUUID(),
                "delete",
                1,
                work.ontologyId(),
                work.ontologyApiName(),
                work.objectTypeId(),
                work.objectTypeApiName(),
                schema.objectTypePhysicalKey(),
                objectId,
                version,
                objectId,
                json.createObjectNode(),
                Instant.now(),
                UUID.randomUUID(),
                "RECONCILIATION",
                true);
    }

    private void record(
            UUID jobId,
            Target target,
            String objectId,
            Difference difference,
            Long authoritativeVersion,
            Long projectedVersion) {
        jdbc.sql("""
                INSERT INTO control.object_instance_reconciliation_differences(
                  job_id,target,object_id,difference_kind,authoritative_version,
                  projected_version,repair_status)
                VALUES (:job,:target,:objectId,:kind,:authoritative,:projected,'PENDING')
                ON CONFLICT DO NOTHING
                """).param("job", jobId)
                .param("target", target.name())
                .param("objectId", objectId)
                .param("kind", difference.name())
                .param("authoritative", authoritativeVersion)
                .param("projected", projectedVersion)
                .update();
    }

    private void complete(
            UUID jobId,
            String status,
            long missing,
            long stale,
            long extra,
            long repaired,
            String error) {
        jdbc.sql("""
                UPDATE control.object_instance_reconciliation_jobs
                SET status=:status,missing_count=:missing,stale_count=:stale,
                    extra_count=:extra,repaired_count=:repaired,safe_error=:error,
                    completed_at=now()
                WHERE id=:id
                """).param("status", status)
                .param("missing", missing)
                .param("stale", stale)
                .param("extra", extra)
                .param("repaired", repaired)
                .param("error", error)
                .param("id", jobId)
                .update();
    }

    private void repaired(
            UUID jobId, Target target, String objectId, Difference difference) {
        jdbc.sql("""
                UPDATE control.object_instance_reconciliation_differences
                SET repair_status='REPAIRED',safe_error=NULL
                WHERE job_id=:job AND target=:target AND object_id=:objectId
                  AND difference_kind=:difference
                """).param("job", jobId)
                .param("target", target.name())
                .param("objectId", objectId)
                .param("difference", difference.name())
                .update();
    }

    private ObjectInstanceReconciliationJob job(UUID id) {
        return jdbc.sql("""
                SELECT * FROM control.object_instance_reconciliation_jobs WHERE id=:id
                """).param("id", id)
                .query((row, number) -> mapJob(row))
                .optional()
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "RECONCILIATION_NOT_FOUND", "Reconciliation job does not exist"));
    }

    private ObjectInstanceReconciliationJob mapJob(java.sql.ResultSet row)
            throws java.sql.SQLException {
        return new ObjectInstanceReconciliationJob()
                .id(row.getObject("id", UUID.class))
                .status(row.getString("status"))
                .missing(row.getLong("missing_count"))
                .stale(row.getLong("stale_count"))
                .extra(row.getLong("extra_count"))
                .repaired(row.getLong("repaired_count"));
    }

    private String safeError(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) {
            value = "Reconciliation failed";
        }
        return value.substring(0, Math.min(1000, value.length()));
    }

    private enum Difference {
        MISSING,
        STALE,
        EXTRA
    }

    private record Work(
            UUID id,
            UUID ontologyId,
            UUID objectTypeId,
            boolean repair,
            String ontologyApiName,
            String objectTypeApiName) {}
}
