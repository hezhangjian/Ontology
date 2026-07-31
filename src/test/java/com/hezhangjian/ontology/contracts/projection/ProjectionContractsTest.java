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
                 "ontology_id":"00000000-0000-0000-0000-00000000a001",
                 "occurred_at":"2026-07-20T00:00:00Z",
                 "producer":"test","correlation_id":"correlation-1","object_type":"Employee",
                 "object_id":"E0001","payload":{"name":"Ada"}}
                """.formatted(eventId);

        OntologyEventEnvelope event = objectMapper.readValue(json, OntologyEventEnvelope.class);

        assertEquals(eventId, event.eventId());
        assertEquals(Instant.parse("2026-07-20T00:00:00Z"), event.occurredAt());
        assertEquals("Ada", event.payload().path("name").asText());
        assertTrue(objectMapper.writeValueAsString(event).contains(
                "\"ontology_id\":\"00000000-0000-0000-0000-00000000a001\""));
    }

    @Test
    void mutationAndRebuildCommandsKeepTypedIdentifiers() throws Exception {
        String mutationJson = """
                {"batch_id":"77777777-7777-4777-8777-777777777777",
                 "ontology_id":"00000000-0000-0000-0000-00000000a001",
                 "action_type_id":"approve","preview_token_id":"preview-1",
                 "idempotency_key":"caller-key",
                 "occurred_at":"2026-07-20T00:00:00Z","correlation_id":"correlation-2",
                 "edits":[{"operation":"object.update","object_type_id":"Employee",
                 "object_id":"E0001","properties":{"department":"Research"}}]}
                """;
        OntologyMutationBatch batch = objectMapper.readValue(mutationJson, OntologyMutationBatch.class);
        IndexRebuildCommand rebuild = objectMapper.readValue(
                """
                {"rebuild_id":"88888888-8888-4888-8888-888888888888",
                 "requested_at":"2026-07-20T00:01:00Z","requested_by":"admin",
                 "correlation_id":"correlation-3"}
                """,
                IndexRebuildCommand.class);

        assertEquals("Research", batch.edits().getFirst().properties().path("department").asText());
        assertEquals(UUID.fromString("88888888-8888-4888-8888-888888888888"), rebuild.rebuildId());
    }
}
