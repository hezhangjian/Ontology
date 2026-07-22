package com.hezhangjian.ontology.projection.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.config.ProjectionProperties;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HugeGraphProjectionClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mergesDuplicateObjectKeysBeforeCallingHugeGraph() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        ProjectionProperties properties = new ProjectionProperties(
                URI.create("http://hugegraph:8080/gremlin"),
                URI.create("http://hugegraph:8080"),
                3,
                URI.create("http://opensearch:9200"),
                URI.create("pulsar://pulsar:6650"),
                Duration.ofSeconds(1));
        HugeGraphProjectionClient client = new HugeGraphProjectionClient(properties, objectMapper, http);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode vertices = response.putArray("vertices");
        vertices.addObject().put("id", "graph-department");
        vertices.addObject().put("id", "graph-team");
        when(http.requireSuccess(eq("PUT"), any(), any())).thenReturn(response);

        List<String> ids = client.applyBatch(List.of(
                validated("department", "研发部", "first"),
                validated("team", "张一鸣", "leader"),
                validated("department", "研发部", "latest")));

        assertEquals(List.of("graph-department", "graph-team", "graph-department"), ids);
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).requireSuccess(eq("PUT"), any(), body.capture());
        assertEquals(2, body.getValue().path("vertices").size());
        assertEquals(
                "latest",
                body.getValue().path("vertices").path(0).path("properties")
                        .path("payload_json").asText().contains("latest") ? "latest" : "");
    }

    private ValidatedEvent validated(String objectType, String objectId, String name) {
        OntologyEventEnvelope event = new OntologyEventEnvelope(
                UUID.randomUUID(),
                "object.upsert",
                1,
                1L,
                Instant.parse("2026-07-21T00:00:00Z"),
                "test",
                "correlation-" + objectId,
                null,
                null,
                objectType,
                objectId,
                1L,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper.createObjectNode().put("name", name),
                null);
        return new ValidatedEvent(
                event,
                "object:" + objectType + ":" + objectId,
                event.objectVersion(),
                event.payload(),
                false,
                false);
    }
}
