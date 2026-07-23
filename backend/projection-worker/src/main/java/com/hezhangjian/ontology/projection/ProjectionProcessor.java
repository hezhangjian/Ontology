package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository.GraphUpdate;
import com.hezhangjian.ontology.projection.model.LedgerEntry;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProjectionProcessor {
    private final ControlPlaneRepository repository;
    private final EventContractValidator validator;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;

    public ProjectionProcessor(
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
        ValidatedEvent validated = validator.validate(event);
        BatchContext context = prepare(topic, messageId, validated);
        if (context.result() != null) {
            return context.result();
        }

        String graphId = context.ledger().graphElementId();
        if (graphId == null || "RECEIVED".equals(context.ledger().status())) {
            graphId = graph.apply(validated);
            repository.graphApplied(event.eventId(), graphId);
        }
        return projectSearch(validated, graphId, context.ledger().attempts());
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

        List<BatchContext> graphPending = contexts.stream()
                .filter(context -> context.result() == null)
                .filter(context -> context.ledger().graphElementId() == null
                        || "RECEIVED".equals(context.ledger().status()))
                .toList();
        applyGraphBatch(graphPending.stream()
                .filter(context -> !context.validated().relation())
                .toList());
        applyGraphBatch(graphPending.stream()
                .filter(context -> context.validated().relation())
                .toList());

        List<BatchContext> searchPending = contexts.stream()
                .filter(context -> context.result() == null)
                .toList();
        try {
            search.applyBatch(
                    searchPending.stream().map(BatchContext::validated).toList(),
                    searchPending.stream().map(context -> context.ledger().graphElementId()).toList());
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

    private BatchContext prepare(String topic, String messageId, ValidatedEvent validated) {
        OntologyEventEnvelope event = validated.event();
        LedgerEntry ledger = repository.register(
                event.eventId(),
                event.eventType(),
                topic,
                messageId,
                event.ontologyId(),
                event.ontologyRevision(),
                validated.entityKey(),
                validated.entityVersion(),
                event.correlationId());
        if (ledger.isTerminal()) {
            return new BatchContext(validated, ledger, new ProjectionResult(ledger.status(), ledger.attempts()));
        }
        ledger = repository.beginAttempt(event.eventId());
        if (repository.newerVersionExists(
                validated.entityKey(), validated.entityVersion(), event.eventId())) {
            repository.stale(event.eventId());
            return new BatchContext(validated, ledger, new ProjectionResult("STALE", ledger.attempts()));
        }
        return new BatchContext(validated, ledger, null);
    }

    private ProjectionResult projectSearch(ValidatedEvent validated, String graphId, int attempts) {
        try {
            search.apply(validated, graphId);
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
        private final ProjectionResult result;

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

        private void setGraphElementId(String graphElementId) {
            ledger = new LedgerEntry(
                    ledger.eventId(),
                    ledger.entityKey(),
                    ledger.entityVersion(),
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
