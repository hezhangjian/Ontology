package com.hezhangjian.ontology.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.instance.ObjectInstanceAuthorityReader;
import com.hezhangjian.ontology.instance.ObjectInstanceAuthorityReader.AuthoritativeObject;
import com.hezhangjian.ontology.security.WorkspaceContext;
import com.hezhangjian.ontology.repo.ControlPlaneRepository;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.ForeignKeyContract;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.ForeignKeyState;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ForeignKeyProjectionCoordinator {
    private final ControlPlaneRepository repository;
    private final ObjectInstanceAuthorityReader objects;
    private final ObjectMapper json;
    private final ObjectProvider<RelationProjectionProcessor> processor;

    public ForeignKeyProjectionCoordinator(
            ControlPlaneRepository repository,
            ObjectInstanceAuthorityReader objects,
            ObjectMapper json,
            ObjectProvider<RelationProjectionProcessor> processor) {
        this.repository = repository;
        this.objects = objects;
        this.json = json;
        this.processor = processor;
    }

    public void reconcile(ValidatedEvent object) {
        if (object.relation()) return;
        OntologyEventEnvelope event = object.event();
        if (object.deleted()) {
            removeSourceRelations(event);
            markTargetRelationsPending(event);
        } else {
            reconcileSourceRelations(event);
            resolvePendingTargetRelations(event);
        }
        repository.refreshForeignKeyHealth(event.ontologyId());
    }

    public String reconcileOntology(UUID ontologyId) {
        int sourceObjects = 0;
        Map<String, ForeignKeyState> states = new LinkedHashMap<>();
        repository.foreignKeyStatesForOntology(ontologyId).forEach(state ->
                states.put(state.relationType() + "\u0000" + state.sourceObjectId(), state));
        for (ForeignKeyContract contract : repository.foreignKeys(ontologyId)) {
            for (AuthoritativeObject object : objects.list(
                    ontologyId, contract.sourceType())) {
                sourceObjects++;
                OntologyEventEnvelope trigger = backfillEvent(
                        ontologyId, contract.sourceType(), object);
                ForeignKeyState old = states.get(
                        contract.relationType() + "\u0000" + object.id());
                JsonNode value = object.payload().get(contract.sourcePropertyId());
                String targetId = value == null || value.isNull() ? "" : value.asText();
                if (targetId.isBlank()) {
                    remove(old, trigger);
                    continue;
                }
                if (old != null && !old.targetObjectId().equals(targetId)) {
                    remove(old, trigger);
                }
                if (old != null && old.targetObjectId().equals(targetId)
                        && "PROJECTED".equals(old.status())) {
                    continue;
                }
                projectIfReady(new ForeignKeyState(
                        ontologyId, contract.relationType(), contract.sourceType(),
                        object.id(), contract.targetType(), targetId,
                        relationId(contract.relationType(), object.id()),
                        "PENDING", null), trigger);
            }
        }
        long projected = repository.foreignKeyStatesForOntology(ontologyId).stream()
                .filter(state -> "PROJECTED".equals(state.status()))
                .count();
        repository.refreshForeignKeyHealth(ontologyId);
        return sourceObjects + " source objects / " + projected + " projected relations";
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5_000)
    public void reconcileOntologies() {
        repository.ontologyIds().forEach(ontologyId ->
                WorkspaceContext.run(ontologyId,
                        () -> reconcileOntology(ontologyId)));
    }

    private void reconcileSourceRelations(OntologyEventEnvelope event) {
        Map<String, ForeignKeyState> previous = new LinkedHashMap<>();
        repository.foreignKeyStatesForSource(
                event.ontologyId(), event.objectType(), event.objectId())
                .forEach(state -> previous.put(state.relationType(), state));
        for (ForeignKeyContract contract : repository.foreignKeys(
                event.ontologyId(), event.objectType())) {
            ForeignKeyState old = previous.remove(contract.relationType());
            JsonNode value = event.payload() == null
                    ? null : event.payload().get(contract.sourcePropertyId());
            String targetId = value == null || value.isNull() ? "" : value.asText();
            if (targetId.isBlank()) {
                remove(old, event);
                continue;
            }
            if (old != null && !old.targetObjectId().equals(targetId)) {
                remove(old, event);
            }
            if (old != null && old.targetObjectId().equals(targetId)
                    && "PROJECTED".equals(old.status())) {
                continue;
            }
            ForeignKeyState desired = new ForeignKeyState(
                    event.ontologyId(), contract.relationType(), contract.sourceType(),
                    event.objectId(), contract.targetType(), targetId,
                    relationId(contract.relationType(), event.objectId()),
                    "PENDING", null);
            projectIfReady(desired, event);
        }
        previous.values().forEach(state -> remove(state, event));
    }

    private void resolvePendingTargetRelations(OntologyEventEnvelope targetEvent) {
        repository.pendingForeignKeysForTarget(
                targetEvent.ontologyId(), targetEvent.objectType(), targetEvent.objectId())
                .forEach(state -> projectIfReady(state, targetEvent));
    }

    private void removeSourceRelations(OntologyEventEnvelope sourceEvent) {
        repository.foreignKeyStatesForSource(
                sourceEvent.ontologyId(), sourceEvent.objectType(), sourceEvent.objectId())
                .forEach(state -> remove(state, sourceEvent));
    }

    private void markTargetRelationsPending(OntologyEventEnvelope targetEvent) {
        repository.foreignKeyStatesForTarget(
                targetEvent.ontologyId(), targetEvent.objectType(), targetEvent.objectId())
                .forEach(state -> {
                    if ("PROJECTED".equals(state.status())) {
                        project(state, "relation.delete", targetEvent);
                    }
                    repository.saveForeignKeyState(withStatus(state, "PENDING", null));
                });
    }

    private void projectIfReady(ForeignKeyState state, OntologyEventEnvelope trigger) {
        if (!objects.exists(
                state.ontologyId(), state.sourceObjectType(), state.sourceObjectId())
                || !objects.exists(
                state.ontologyId(), state.targetObjectType(), state.targetObjectId())) {
            repository.saveForeignKeyState(withStatus(state, "PENDING", null));
            return;
        }
        try {
            project(state, "relation.upsert", trigger);
            repository.saveForeignKeyState(withStatus(state, "PROJECTED", null));
        } catch (RuntimeException failure) {
            repository.saveForeignKeyState(withStatus(
                    state, "PENDING", safe(failure.getMessage())));
        }
    }

    private void remove(ForeignKeyState state, OntologyEventEnvelope trigger) {
        if (state == null) return;
        if ("PROJECTED".equals(state.status())) {
            try {
                project(state, "relation.delete", trigger);
            } catch (RuntimeException failure) {
                repository.saveForeignKeyState(withStatus(
                        state, "PENDING", safe(failure.getMessage())));
                return;
            }
        }
        repository.deleteForeignKeyState(state);
    }

    private void project(ForeignKeyState state, String eventType, OntologyEventEnvelope trigger) {
        String seed = trigger.eventId() + ":" + eventType + ":" + state.relationType()
                + ":" + state.sourceObjectId() + ":" + state.targetObjectId();
        OntologyEventEnvelope relation = new OntologyEventEnvelope(
                UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
                eventType,
                1,
                state.ontologyId(),
                Instant.now(),
                "ontology-core/fk-projection",
                trigger.correlationId(),
                trigger.traceId(),
                trigger.flinkJobId(),
                null,
                null,
                state.relationType(),
                state.relationId(),
                state.sourceObjectType(),
                state.sourceObjectId(),
                state.targetObjectType(),
                state.targetObjectId(),
                json.createObjectNode(),
                trigger.source());
        processor.getObject().process("ontology.fk-relations", seed, relation);
    }

    private ForeignKeyState withStatus(
            ForeignKeyState state, String status, String error) {
        return new ForeignKeyState(state.ontologyId(), state.relationType(),
                state.sourceObjectType(), state.sourceObjectId(), state.targetObjectType(),
                state.targetObjectId(), state.relationId(),
                status, error);
    }

    private String relationId(String relationType, String sourceObjectId) {
        return "fk:" + relationType + ":" + sourceObjectId;
    }

    private OntologyEventEnvelope backfillEvent(
            UUID ontologyId,
            String objectType,
            AuthoritativeObject object) {
        String seed = ontologyId + ":" + objectType + ":" + object.id();
        return new OntologyEventEnvelope(
                UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)),
                "object.upsert",
                1,
                ontologyId,
                Instant.now(),
                "ontology-core/fk-backfill",
                "fk-backfill:" + ontologyId,
                null,
                null,
                objectType,
                object.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                object.payload(),
                null);
    }

    private String safe(String message) {
        String value = Objects.toString(message, "Foreign-key relation projection failed")
                .replaceAll("[\\r\\n]+", " ");
        return value.substring(0, Math.min(value.length(), 1000));
    }
}
