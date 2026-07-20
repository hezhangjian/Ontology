package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
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
        List<ValidatedEvent> validatedEvents = events.stream().map(validator::validate).toList();
        List<BatchContext> contexts = new ArrayList<>();
        for (int index = 0; index < validatedEvents.size(); index++) {
            contexts.add(prepare(topic, messageId + ":" + index, validatedEvents.get(index)));
        }

        List<BatchContext> graphPending = contexts.stream()
                .filter(context -> context.result() == null)
                .filter(context -> context.ledger().graphElementId() == null
                        || "RECEIVED".equals(context.ledger().status()))
                .toList();
        List<String> graphIds = graph.applyBatch(graphPending.stream()
                .map(BatchContext::validated)
                .toList());
        for (int index = 0; index < graphPending.size(); index++) {
            BatchContext context = graphPending.get(index);
            String graphId = graphIds.get(index);
            repository.graphApplied(context.validated().event().eventId(), graphId);
            context.setGraphElementId(graphId);
        }

        List<ProjectionResult> results = new ArrayList<>();
        for (BatchContext context : contexts) {
            if (context.result() != null) {
                results.add(context.result());
                continue;
            }
            results.add(projectSearch(
                    context.validated(),
                    context.ledger().graphElementId(),
                    context.ledger().attempts()));
        }
        return List.copyOf(results);
    }

    private BatchContext prepare(String topic, String messageId, ValidatedEvent validated) {
        OntologyEventEnvelope event = validated.event();
        LedgerEntry ledger = repository.register(
                event.eventId(),
                event.eventType(),
                topic,
                messageId,
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
}
