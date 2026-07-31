package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.repo.ControlPlaneRepository;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.GraphUpdate;
import com.hezhangjian.ontology.projection.model.LedgerEntry;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient.ApplyResult;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class RelationProjectionProcessor {
    private final ControlPlaneRepository repository;
    private final EventContractValidator validator;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;

    public RelationProjectionProcessor(
            ControlPlaneRepository repository,
            EventContractValidator validator,
            HugeGraphProjectionClient graph,
            OpenSearchProjectionClient search) {
        this.repository = repository;
        this.validator = validator;
        this.graph = graph;
        this.search = search;
    }

    public ProjectionResult process(String topic, String messageId, OntologyEventEnvelope event) {
        BatchContext context = prepare(topic, messageId, validator.validate(event));
        if (context.result() != null) {
            return context.result();
        }
        ValidatedEvent validated = context.validated();

        String graphId = context.ledger().graphElementId();
        if (graphId == null || "RECEIVED".equals(context.ledger().status())) {
            graphId = graph.apply(validated);
            repository.graphApplied(event.eventId(), graphId);
        }
        ProjectionResult result = projectSearch(validated, graphId, context.ledger().attempts());
        return result;
    }

    public List<ProjectionResult> processBatch(
            String topic,
            String messageId,
            List<OntologyEventEnvelope> events) {
        List<ProjectionInput> inputs = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            inputs.add(new ProjectionInput(topic, messageId + ":" + index, events.get(index)));
        }
        return processBatch(inputs);
    }

    public List<ProjectionResult> processBatch(List<ProjectionInput> inputs) {
        List<ValidatedEvent> validatedEvents = inputs.stream()
                .map(ProjectionInput::event)
                .map(validator::validate)
                .toList();
        List<BatchContext> contexts = new ArrayList<>();
        for (int index = 0; index < validatedEvents.size(); index++) {
            ProjectionInput input = inputs.get(index);
            contexts.add(prepare(input.topic(), input.messageId(), validatedEvents.get(index)));
        }
        markSupersededBatchVersions(contexts);

        List<BatchContext> graphPending = contexts.stream()
                .filter(context -> context.result() == null)
                .filter(context -> context.ledger().graphElementId() == null
                        || "RECEIVED".equals(context.ledger().status()))
                .toList();
        applyGraphBatch(graphPending);

        List<BatchContext> searchPending = contexts.stream()
                .filter(context -> context.result() == null)
                .toList();
        try {
            List<ApplyResult> searchResults = search.applyBatch(
                    searchPending.stream().map(BatchContext::validated).toList(),
                    searchPending.stream().map(context -> context.ledger().graphElementId()).toList());
            for (int index = 0; index < searchPending.size(); index++) {
                BatchContext context = searchPending.get(index);
                if (searchResults.get(index) == ApplyResult.STALE) {
                    repository.stale(context.validated().event().eventId());
                    context.setResult(new ProjectionResult("STALE", context.ledger().attempts()));
                }
            }
        } catch (ProjectionException exception) {
            for (BatchContext context : searchPending) {
                repository.degraded(
                        context.validated().event().eventId(),
                        exception.code(),
                        exception.getMessage());
            }
            throw exception;
        }

        repository.projectedBatch(searchPending.stream()
                .filter(context -> context.result() == null)
                .map(context -> context.validated().event().eventId())
                .toList());
        List<ProjectionResult> results = new ArrayList<>();
        for (BatchContext context : contexts) {
            if (context.result() != null) {
                results.add(context.result());
                continue;
            }
            results.add(new ProjectionResult("PROJECTED", context.ledger().attempts()));
        }
        return List.copyOf(results);
    }

    private void applyGraphBatch(List<BatchContext> contexts) {
        if (contexts.isEmpty()) {
            return;
        }
        List<String> graphIds = graph.applyBatch(contexts.stream()
                .map(BatchContext::validated)
                .toList());
        for (int index = 0; index < contexts.size(); index++) {
            contexts.get(index).setGraphElementId(graphIds.get(index));
        }
        repository.graphAppliedBatch(contexts.stream()
                .map(context -> new GraphUpdate(
                        context.validated().event().eventId(),
                        context.ledger().graphElementId()))
                .toList());
    }

    private void markSupersededBatchVersions(List<BatchContext> contexts) {
        Map<String, Long> highestSequences = new HashMap<>();
        contexts.stream()
                .filter(context -> context.result() == null)
                .forEach(context -> highestSequences.merge(
                        context.validated().entityKey(),
                        context.validated().projectionSequence(),
                        Math::max));
        contexts.stream()
                .filter(context -> context.result() == null)
                .filter(context -> context.validated().projectionSequence()
                        < highestSequences.get(context.validated().entityKey()))
                .forEach(context -> {
                    repository.stale(context.validated().event().eventId());
                    context.setResult(new ProjectionResult("STALE", context.ledger().attempts()));
                });
    }

    private BatchContext prepare(String topic, String messageId, ValidatedEvent validated) {
        if (!validated.relation()) {
            throw new IllegalArgumentException(
                    "Relation projection processor accepts relation events only");
        }
        OntologyEventEnvelope event = validated.event();
        LedgerEntry ledger = repository.register(
                event.eventId(),
                event.eventType(),
                topic,
                messageId,
                event.ontologyId(),
                validated.entityKey(),
                event.correlationId());
        validated = validated.withProjectionSequence(ledger.projectionSequence());
        if (ledger.isTerminal()) {
            return new BatchContext(validated, ledger, new ProjectionResult(ledger.status(), ledger.attempts()));
        }
        ledger = repository.beginAttempt(event.eventId());
        if (repository.newerSequenceExists(
                validated.entityKey(), validated.projectionSequence(), event.eventId())) {
            repository.stale(event.eventId());
            return new BatchContext(validated, ledger, new ProjectionResult("STALE", ledger.attempts()));
        }
        return new BatchContext(validated, ledger, null);
    }

    private ProjectionResult projectSearch(ValidatedEvent validated, String graphId, int attempts) {
        try {
            if (search.apply(validated, graphId) == ApplyResult.STALE) {
                repository.stale(validated.event().eventId());
                return new ProjectionResult("STALE", attempts);
            }
            repository.projected(validated.event().eventId());
            return new ProjectionResult("PROJECTED", attempts);
        } catch (ProjectionException exception) {
            repository.degraded(validated.event().eventId(), exception.code(), exception.getMessage());
            throw exception;
        }
    }

    private static final class BatchContext {
        private final ValidatedEvent validated;
        private LedgerEntry ledger;
        private ProjectionResult result;

        private BatchContext(ValidatedEvent validated, LedgerEntry ledger, ProjectionResult result) {
            this.validated = validated;
            this.ledger = ledger;
            this.result = result;
        }

        private ValidatedEvent validated() {
            return validated;
        }

        private LedgerEntry ledger() {
            return ledger;
        }

        private ProjectionResult result() {
            return result;
        }

        private void setResult(ProjectionResult result) {
            this.result = result;
        }

        private void setGraphElementId(String graphElementId) {
            ledger = new LedgerEntry(
                    ledger.eventId(),
                    ledger.entityKey(),
                    ledger.projectionSequence(),
                    "GRAPH_APPLIED",
                    ledger.attempts(),
                    graphElementId);
        }
    }

    public record ProjectionResult(String status, int attempts) {
    }

    public record ProjectionInput(String topic, String messageId, OntologyEventEnvelope event) {
    }
}
