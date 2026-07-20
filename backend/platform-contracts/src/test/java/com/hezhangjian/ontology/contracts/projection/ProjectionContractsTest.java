package com.hezhangjian.ontology.contracts.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionContractsTest {
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void eventEnvelopeUsesFrozenSnakeCaseWireNames() throws Exception {
        UUID eventId = UUID.fromString("d5fb2f89-1727-43e7-8d4a-9938843ca707");
        String json = """
                {"event_id":"%s","event_type":"object.upsert","schema_version":1,
                 "ontology_revision":1,"occurred_at":"2026-07-20T00:00:00Z",
                 "producer":"test","correlation_id":"correlation-1","object_type":"Employee",
                 "object_id":"E0001","object_version":1,"payload":{"name":"Ada"}}
                """.formatted(eventId);

        OntologyEventEnvelope event = objectMapper.readValue(json, OntologyEventEnvelope.class);

        assertEquals(eventId, event.eventId());
        assertEquals(Instant.parse("2026-07-20T00:00:00Z"), event.occurredAt());
        assertEquals("Ada", event.payload().path("name").asText());
        assertTrue(objectMapper.writeValueAsString(event).contains("\"ontology_revision\":1"));
    }

    @Test
    void mutationAndRebuildCommandsKeepTypedIdentifiers() throws Exception {
        String mutationJson = """
                {"batch_id":"77777777-7777-4777-8777-777777777777","ontology_revision":1,
                 "action_type_id":"approve","action_version":2,"preview_token_id":"preview-1",
                 "idempotency_key":"caller-key","requested_by":"admin",
                 "occurred_at":"2026-07-20T00:00:00Z","correlation_id":"correlation-2",
                 "edits":[{"operation":"object.update","object_type_id":"Employee",
                 "object_id":"E0001","expected_version":3,"properties":{"department":"Research"}}]}
                """;
        OntologyMutationBatch batch = objectMapper.readValue(mutationJson, OntologyMutationBatch.class);
        IndexRebuildCommand rebuild = objectMapper.readValue(
                """
                {"rebuild_id":"88888888-8888-4888-8888-888888888888",
                 "requested_at":"2026-07-20T00:01:00Z","requested_by":"admin",
                 "correlation_id":"correlation-3"}
                """,
                IndexRebuildCommand.class);

        assertEquals(3, batch.edits().getFirst().expectedVersion());
        assertEquals("Research", batch.edits().getFirst().properties().path("department").asText());
        assertEquals(UUID.fromString("88888888-8888-4888-8888-888888888888"), rebuild.rebuildId());
    }
}
