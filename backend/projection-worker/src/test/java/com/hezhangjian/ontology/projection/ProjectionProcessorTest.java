package com.hezhangjian.ontology.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
import com.hezhangjian.ontology.projection.model.LedgerEntry;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ProjectionProcessorTest {
    private final ControlPlaneRepository repository = mock(ControlPlaneRepository.class);
    private final EventContractValidator validator = mock(EventContractValidator.class);
    private final HugeGraphProjectionClient graph = mock(HugeGraphProjectionClient.class);
    private final OpenSearchProjectionClient search = mock(OpenSearchProjectionClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ProjectionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new ProjectionProcessor(repository, validator, graph, search);
    }

    @Test
    void skipsAnAlreadyProjectedEventWithoutTouchingStorage() {
        OntologyEventEnvelope event = event("10000000-0000-4000-8000-000000000001", "E-1", 1);
        ValidatedEvent validated = validated(event);
        when(validator.validate(event)).thenReturn(validated);
        when(repository.register(any(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyString()))
                .thenReturn(ledger(event, "PROJECTED", 1, "graph-1"));

        ProjectionProcessor.ProjectionResult result = processor.process("topic", "message", event);

        assertEquals("PROJECTED", result.status());
        assertEquals(1, result.attempts());
        verify(graph, never()).apply(any());
        verify(search, never()).apply(any(), anyString());
    }

    @Test
    void appliesOneGraphTransactionBeforeIndexingEveryBatchEdit() {
        OntologyEventEnvelope first = event("10000000-0000-4000-8000-000000000002", "E-2", 1);
        OntologyEventEnvelope second = event("10000000-0000-4000-8000-000000000003", "E-3", 1);
        ValidatedEvent firstValidated = validated(first);
        ValidatedEvent secondValidated = validated(second);
        when(validator.validate(first)).thenReturn(firstValidated);
        when(validator.validate(second)).thenReturn(secondValidated);
        when(repository.register(any(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyString()))
                .thenReturn(ledger(first, "RECEIVED", 0, null), ledger(second, "RECEIVED", 0, null));
        when(repository.beginAttempt(first.eventId())).thenReturn(ledger(first, "RECEIVED", 1, null));
        when(repository.beginAttempt(second.eventId())).thenReturn(ledger(second, "RECEIVED", 1, null));
        when(graph.applyBatch(List.of(firstValidated, secondValidated))).thenReturn(List.of("graph-2", "graph-3"));

        List<ProjectionProcessor.ProjectionResult> results = processor.processBatch(
                "topic", "message", List.of(first, second));

        assertEquals(List.of("PROJECTED", "PROJECTED"), results.stream()
                .map(ProjectionProcessor.ProjectionResult::status)
                .toList());
        InOrder ordered = inOrder(graph, repository, search);
        ordered.verify(graph).applyBatch(List.of(firstValidated, secondValidated));
        ordered.verify(repository).graphApplied(first.eventId(), "graph-2");
        ordered.verify(repository).graphApplied(second.eventId(), "graph-3");
        ordered.verify(search).apply(firstValidated, "graph-2");
        ordered.verify(search).apply(secondValidated, "graph-3");
    }

    @Test
    void preservesGraphProgressWhenSearchIsUnavailable() {
        OntologyEventEnvelope event = event("10000000-0000-4000-8000-000000000004", "E-4", 1);
        ValidatedEvent validated = validated(event);
        ProjectionException unavailable = new ProjectionException("SEARCH_UNAVAILABLE", "search unavailable", true);
        when(validator.validate(event)).thenReturn(validated);
        when(repository.register(any(), anyString(), anyString(), anyString(), anyLong(),
                anyString(), anyLong(), anyString()))
                .thenReturn(ledger(event, "RECEIVED", 0, null));
        when(repository.beginAttempt(event.eventId())).thenReturn(ledger(event, "RECEIVED", 1, null));
        when(graph.apply(validated)).thenReturn("graph-4");
        org.mockito.Mockito.doThrow(unavailable).when(search).apply(validated, "graph-4");

        assertThrows(ProjectionException.class, () -> processor.process("topic", "message", event));

        InOrder ordered = inOrder(graph, repository, search);
        ordered.verify(graph).apply(validated);
        ordered.verify(repository).graphApplied(event.eventId(), "graph-4");
        ordered.verify(search).apply(validated, "graph-4");
        verify(repository).degraded(event.eventId(), "SEARCH_UNAVAILABLE", "search unavailable");
    }

    private OntologyEventEnvelope event(String eventId, String objectId, long version) {
        return new OntologyEventEnvelope(
                UUID.fromString(eventId),
                "object.upsert",
                1,
                1,
                Instant.parse("2026-07-20T00:00:00Z"),
                "test",
                "correlation-" + objectId,
                null,
                null,
                "Employee",
                objectId,
                version,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper.createObjectNode().put("name", objectId),
                null);
    }

    private ValidatedEvent validated(OntologyEventEnvelope event) {
        return new ValidatedEvent(
                event,
                "object:Employee:" + event.objectId(),
                event.objectVersion(),
                event.payload(),
                false,
                false);
    }

    private LedgerEntry ledger(
            OntologyEventEnvelope event,
            String status,
            int attempts,
            String graphElementId) {
        return new LedgerEntry(
                event.eventId(),
                "object:Employee:" + event.objectId(),
                event.objectVersion(),
                status,
                attempts,
                graphElementId);
    }
}
