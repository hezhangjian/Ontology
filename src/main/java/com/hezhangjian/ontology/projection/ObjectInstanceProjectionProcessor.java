package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.EventSource;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.instance.ObjectInstanceEvent;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import org.springframework.stereotype.Service;

@Service
public class ObjectInstanceProjectionProcessor {
    public enum Target {
        HUGEGRAPH,
        OPENSEARCH
    }

    private final EventContractValidator validator;
    private final ForeignKeyProjectionCoordinator foreignKeys;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;
    private final SqlClientRepository jdbc;

    public ObjectInstanceProjectionProcessor(
            EventContractValidator validator,
            ForeignKeyProjectionCoordinator foreignKeys,
            HugeGraphProjectionClient graph,
            OpenSearchProjectionClient search,
            SqlClientRepository jdbc) {
        this.validator = validator;
        this.foreignKeys = foreignKeys;
        this.graph = graph;
        this.search = search;
        this.jdbc = jdbc;
    }

    public boolean process(Target target, ObjectInstanceEvent event) {
        return apply(target, event, false);
    }

    public boolean repair(Target target, ObjectInstanceEvent event) {
        return apply(target, event, true);
    }

    private boolean apply(Target target, ObjectInstanceEvent event, boolean force) {
        long current = currentVersion(target, event);
        if (!force && current >= event.version()) {
            return false;
        }
        try {
            var validated = validator.validate(envelope(event))
                    .withProjectionSequence(event.version());
            if (target == Target.HUGEGRAPH) {
                graph.apply(validated);
                foreignKeys.reconcile(validated);
            } else if (force) {
                search.repair(validated, null);
            } else {
                search.apply(validated, null);
            }
            projected(target, event);
            return true;
        } catch (RuntimeException failure) {
            degraded(target, event, failure);
            throw failure;
        }
    }

    public void dlq(Target target, ObjectInstanceEvent event, Throwable failure) {
        jdbc.sql("""
                INSERT INTO control.object_instance_projection_state(
                  target,ontology_id,object_type_id,object_id,projected_version,
                  status,last_event_id,last_error)
                VALUES (:target,:ontology,:objectType,:objectId,0,'DLQ',:eventId,:error)
                ON CONFLICT(target,ontology_id,object_type_id,object_id) DO UPDATE
                SET status='DLQ',last_event_id=excluded.last_event_id,
                    last_error=excluded.last_error,updated_at=now()
                """).param("target", target.name())
                .param("ontology", event.ontologyId())
                .param("objectType", event.objectTypeId())
                .param("objectId", event.objectId())
                .param("eventId", event.eventId())
                .param("error", safeError(failure))
                .update();
    }

    private long currentVersion(Target target, ObjectInstanceEvent event) {
        return jdbc.sql("""
                SELECT projected_version
                FROM control.object_instance_projection_state
                WHERE target=:target AND ontology_id=:ontology
                  AND object_type_id=:objectType AND object_id=:objectId
                """).param("target", target.name())
                .param("ontology", event.ontologyId())
                .param("objectType", event.objectTypeId())
                .param("objectId", event.objectId())
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private void projected(Target target, ObjectInstanceEvent event) {
        jdbc.sql("""
                INSERT INTO control.object_instance_projection_state(
                  target,ontology_id,object_type_id,object_id,projected_version,
                  status,last_event_id,last_error)
                VALUES (:target,:ontology,:objectType,:objectId,:version,
                  'PROJECTED',:eventId,NULL)
                ON CONFLICT(target,ontology_id,object_type_id,object_id) DO UPDATE
                SET projected_version=GREATEST(
                      control.object_instance_projection_state.projected_version,
                      excluded.projected_version),
                    status=CASE WHEN excluded.projected_version >=
                      control.object_instance_projection_state.projected_version
                      THEN 'PROJECTED' ELSE control.object_instance_projection_state.status END,
                    last_event_id=excluded.last_event_id,last_error=NULL,updated_at=now()
                """).param("target", target.name())
                .param("ontology", event.ontologyId())
                .param("objectType", event.objectTypeId())
                .param("objectId", event.objectId())
                .param("version", event.version())
                .param("eventId", event.eventId())
                .update();
    }

    private void degraded(Target target, ObjectInstanceEvent event, Throwable failure) {
        jdbc.sql("""
                INSERT INTO control.object_instance_projection_state(
                  target,ontology_id,object_type_id,object_id,projected_version,
                  status,last_event_id,last_error)
                VALUES (:target,:ontology,:objectType,:objectId,0,
                  'DEGRADED',:eventId,:error)
                ON CONFLICT(target,ontology_id,object_type_id,object_id) DO UPDATE
                SET status='DEGRADED',last_event_id=excluded.last_event_id,
                    last_error=excluded.last_error,updated_at=now()
                """).param("target", target.name())
                .param("ontology", event.ontologyId())
                .param("objectType", event.objectTypeId())
                .param("objectId", event.objectId())
                .param("eventId", event.eventId())
                .param("error", safeError(failure))
                .update();
    }

    private OntologyEventEnvelope envelope(ObjectInstanceEvent event) {
        return new OntologyEventEnvelope(
                event.eventId(),
                event.deleted() ? "object.delete" : "object.upsert",
                event.schemaVersion(),
                event.ontologyId(),
                event.occurredAt(),
                "ontology-core/object-instance",
                event.correlationId().toString(),
                event.correlationId().toString(),
                null,
                event.objectTypePhysicalKey(),
                event.objectId(),
                null,
                null,
                null,
                null,
                null,
                null,
                event.properties(),
                new EventSource(null, null, event.source()));
    }

    private String safeError(Throwable failure) {
        String value = failure.getMessage();
        if (value == null || value.isBlank()) {
            value = "Projection failed";
        }
        return value.substring(0, Math.min(1000, value.length()));
    }
}
