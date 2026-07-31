package com.hezhangjian.ontology.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.model.LedgerEntry;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient.ApplyResult;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import com.hezhangjian.ontology.repo.ControlPlaneRepository;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.GraphUpdate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class RelationProjectionProcessorTest {
    private final ControlPlaneRepository repository = mock(ControlPlaneRepository.class);
    private final EventContractValidator validator = mock(EventContractValidator.class);
    private final HugeGraphProjectionClient graph = mock(HugeGraphProjectionClient.class);
    private final OpenSearchProjectionClient search = mock(OpenSearchProjectionClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private RelationProjectionProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RelationProjectionProcessor(repository, validator, graph, search);
        when(validator.validate(any())).thenAnswer(invocation -> {
            OntologyEventEnvelope event = invocation.getArgument(0);
            boolean relation = event.relationType() != null;
            return new ValidatedEvent(
                    event,
                    relation
                            ? "relation:" + event.relationType() + ":" + event.relationId()
                            : "object:" + event.objectType() + ":" + event.objectId(),
                    1,
                    event.payload(),
                    false,
                    relation);
        });
    }

    @Test
    void rejectsObjectEvents() {
        assertThatThrownBy(() -> processor.process("topic", "message", objectEvent()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relation events only");
        verify(repository, never()).register(
                any(), anyString(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void skipsAlreadyProjectedRelation() {
        OntologyEventEnvelope event = relationEvent(1);
        when(repository.register(
                        any(), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(ledger(event, "PROJECTED", 1, "graph-1", 1));

        var result = processor.process("topic", "message", event);

        assertThat(result.status()).isEqualTo("PROJECTED");
        verify(graph, never()).apply(any());
        verify(search, never()).apply(any(), anyString());
    }

    @Test
    void appliesRelationBatchToGraphBeforeSearch() {
        OntologyEventEnvelope first = relationEvent(1);
        OntologyEventEnvelope second = relationEvent(2);
        ValidatedEvent firstValidated = validated(first, 1);
        ValidatedEvent secondValidated = validated(second, 1);
        when(validator.validate(first)).thenReturn(firstValidated);
        when(validator.validate(second)).thenReturn(secondValidated);
        when(repository.register(
                        any(), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(
                        ledger(first, "RECEIVED", 0, null, 1),
                        ledger(second, "RECEIVED", 0, null, 1));
        when(repository.beginAttempt(first.eventId()))
                .thenReturn(ledger(first, "RECEIVED", 1, null, 1));
        when(repository.beginAttempt(second.eventId()))
                .thenReturn(ledger(second, "RECEIVED", 1, null, 1));
        when(graph.applyBatch(List.of(firstValidated, secondValidated)))
                .thenReturn(List.of("graph-1", "graph-2"));
        when(search.applyBatch(
                        List.of(firstValidated, secondValidated),
                        List.of("graph-1", "graph-2")))
                .thenReturn(List.of(ApplyResult.APPLIED, ApplyResult.APPLIED));

        var results = processor.processBatch("topic", "message", List.of(first, second));

        assertThat(results).extracting(RelationProjectionProcessor.ProjectionResult::status)
                .containsExactly("PROJECTED", "PROJECTED");
        InOrder ordered = inOrder(graph, repository, search);
        ordered.verify(graph).applyBatch(List.of(firstValidated, secondValidated));
        ordered.verify(repository).graphAppliedBatch(List.of(
                new GraphUpdate(first.eventId(), "graph-1"),
                new GraphUpdate(second.eventId(), "graph-2")));
        ordered.verify(search).applyBatch(
                List.of(firstValidated, secondValidated),
                List.of("graph-1", "graph-2"));
    }

    @Test
    void preservesGraphProgressWhenSearchFails() {
        OntologyEventEnvelope event = relationEvent(1);
        ValidatedEvent validated = validated(event, 1);
        when(validator.validate(event)).thenReturn(validated);
        when(repository.register(
                        any(), anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(ledger(event, "RECEIVED", 0, null, 1));
        when(repository.beginAttempt(event.eventId()))
                .thenReturn(ledger(event, "RECEIVED", 1, null, 1));
        when(graph.apply(validated)).thenReturn("graph-1");
        ProjectionException unavailable =
                new ProjectionException("SEARCH_UNAVAILABLE", "search unavailable", true);
        org.mockito.Mockito.doThrow(unavailable).when(search).apply(validated, "graph-1");

        assertThatThrownBy(() -> processor.process("topic", "message", event))
                .isSameAs(unavailable);

        verify(repository).graphApplied(event.eventId(), "graph-1");
        verify(repository).degraded(
                event.eventId(), "SEARCH_UNAVAILABLE", "search unavailable");
    }

    private OntologyEventEnvelope relationEvent(int suffix) {
        return new OntologyEventEnvelope(
                UUID.fromString("10000000-0000-4000-8000-00000000000" + suffix),
                "relation.upsert",
                1,
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                Instant.parse("2026-07-20T00:00:00Z"),
                "test",
                "correlation-R-" + suffix,
                null,
                null,
                null,
                null,
                "WorksAt",
                "R-" + suffix,
                "Employee",
                "E-" + suffix,
                "Company",
                "C-" + suffix,
                json.createObjectNode(),
                null);
    }

    private OntologyEventEnvelope objectEvent() {
        return new OntologyEventEnvelope(
                UUID.fromString("10000000-0000-4000-8000-000000000009"),
                "object.upsert",
                1,
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                Instant.parse("2026-07-20T00:00:00Z"),
                "test",
                "correlation-object",
                null,
                null,
                "Employee",
                "E-1",
                null,
                null,
                null,
                null,
                null,
                null,
                json.createObjectNode(),
                null);
    }

    private ValidatedEvent validated(OntologyEventEnvelope event, long sequence) {
        return new ValidatedEvent(
                event,
                "relation:" + event.relationType() + ":" + event.relationId(),
                sequence,
                event.payload(),
                false,
                true);
    }

    private LedgerEntry ledger(
            OntologyEventEnvelope event,
            String status,
            int attempts,
            String graphElementId,
            long sequence) {
        return new LedgerEntry(
                event.eventId(),
                "relation:" + event.relationType() + ":" + event.relationId(),
                sequence,
                status,
                attempts,
                graphElementId);
    }
}
