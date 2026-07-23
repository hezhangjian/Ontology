package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.MutationEdit;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.contracts.projection.OntologyMutationBatch;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository.ProjectionOperation;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MutationBatchProcessor {
    private static final int MAX_EDITS = 100;
    private final ControlPlaneRepository repository;
    private final ProjectionProcessor processor;

    public MutationBatchProcessor(ControlPlaneRepository repository, ProjectionProcessor processor) {
        this.repository = repository;
        this.processor = processor;
    }

    public void process(String topic, String messageId, OntologyMutationBatch batch) {
        validate(batch);
        ProjectionOperation operation = repository.registerOperation(
                batch.batchId(),
                batch.idempotencyKey(),
                batch.ontologyId(),
                batch.ontologyRevision(),
                batch.requestedBy(),
                batch.correlationId(),
                batch.edits().size());
        if (operation.projected()) {
            return;
        }
        repository.updateOperation(operation.operationId(), "PROJECTING");
        try {
            List<OntologyEventEnvelope> events = new java.util.ArrayList<>();
            for (int index = 0; index < batch.edits().size(); index++) {
                events.add(toEvent(batch, batch.edits().get(index), index));
            }
            List<ProjectionProcessor.ProjectionResult> results = processor.processBatch(topic, messageId, events);
            if (results.stream().anyMatch(result -> !"PROJECTED".equals(result.status()))) {
                throw new ProjectionException(
                        "MUTATION_EDIT_NOT_PROJECTED",
                        "One or more mutation edits did not reach the projected state",
                        false);
            }
            repository.updateOperation(operation.operationId(), "PROJECTED");
        } catch (RuntimeException exception) {
            boolean retryable = exception instanceof ProjectionException failure && failure.retryable();
            repository.updateOperation(operation.operationId(), retryable ? "DEGRADED" : "FAILED");
            throw exception;
        }
    }

    private void validate(OntologyMutationBatch batch) {
        if (batch.batchId() == null
                || batch.ontologyId() == null
                || batch.ontologyRevision() <= 0
                || !StringUtils.hasText(batch.idempotencyKey())
                || !StringUtils.hasText(batch.requestedBy())
                || !StringUtils.hasText(batch.correlationId())
                || batch.occurredAt() == null
                || batch.edits() == null
                || batch.edits().isEmpty()
                || batch.edits().size() > MAX_EDITS) {
            throw new ProjectionException("MUTATION_BATCH_INVALID", "Mutation batch contract is invalid", false);
        }
    }

    private OntologyEventEnvelope toEvent(OntologyMutationBatch batch, MutationEdit edit, int index) {
        String eventType = switch (edit.operation()) {
            case "object.create", "object.update" -> "object.upsert";
            case "object.delete" -> "object.delete";
            case "relation.create", "relation.update" -> "relation.upsert";
            case "relation.delete" -> "relation.delete";
            default -> throw new ProjectionException(
                    "MUTATION_EDIT_INVALID", "Unsupported mutation edit operation", false);
        };
        long version = edit.expectedVersion() == null ? 1 : edit.expectedVersion() + 1;
        UUID eventId = UUID.nameUUIDFromBytes(
                (batch.idempotencyKey() + ":" + index).getBytes(StandardCharsets.UTF_8));
        return new OntologyEventEnvelope(
                eventId,
                eventType,
                1,
                batch.ontologyId(),
                batch.ontologyRevision(),
                batch.occurredAt(),
                "ontology-core/action/" + batch.actionTypeId(),
                batch.correlationId(),
                null,
                null,
                edit.objectTypeId(),
                edit.objectId(),
                eventType.startsWith("object.") ? version : null,
                edit.relationTypeId(),
                edit.relationId(),
                eventType.startsWith("relation.") ? version : null,
                edit.sourceObjectTypeId(),
                edit.sourceObjectId(),
                edit.targetObjectTypeId(),
                edit.targetObjectId(),
                edit.properties(),
                null);
    }
}
