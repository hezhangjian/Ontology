package com.hezhangjian.ontology.projection.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.config.HugeGraphProperties;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.repo.ControlPlaneRepository;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.ObjectSchema;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.PropertyContract;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.RelationSchema;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HugeGraphProjectionClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mergesDuplicateObjectKeysBeforeCallingHugeGraph() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        HugeGraphProperties properties = properties();
        HugeGraphProjectionClient client = client(properties, http);
        stubSchema(http);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("vertices").addObject().put("id", "graph-object");
        when(http.requireSuccess(eq("PUT"), any(), any())).thenReturn(response);

        List<String> ids = client.applyBatch(List.of(
                validated("department", "研发部", "first"),
                validated("team", "张一鸣", "leader"),
                validated("department", "研发部", "latest")));

        assertEquals(List.of("graph-object", "graph-object", "graph-object"), ids);
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http, times(2)).requireSuccess(eq("PUT"), any(), body.capture());
        JsonNode department = body.getAllValues().stream()
                .filter(value -> "department".equals(
                        value.path("vertices").path(0).path("label").asText()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, department.path("vertices").size());
        assertEquals(
                "latest",
                department.path("vertices").path(0).path("properties")
                        .path("name").asText());
        assertEquals(
                "2026-07-21 00:00:00.000",
                department.path("vertices").path(0).path("properties")
                        .path("occurred_at").asText());
    }

    @Test
    void writesRelationUpsertsWithTheNativeEdgeBatchApi() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        HugeGraphProperties properties = properties();
        HugeGraphProjectionClient client = client(properties, http);
        stubSchema(http);
        when(http.exchange(eq("GET"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(
                        200, objectMapper.createObjectNode().put("id", "graph-object"), ""));
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("edges").addObject().put("id", "graph-relation");
        when(http.requireSuccess(eq("PUT"), any(), any())).thenReturn(response);

        assertEquals(List.of("graph-relation"), client.applyBatch(List.of(validatedRelation())));

        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(http).requireSuccess(eq("PUT"), any(), body.capture());
        assertEquals(1, body.getValue().path("edges").size());
        assertEquals(true, body.getValue().path("check_vertex").asBoolean());
        assertEquals(
                "2026-07-21 00:00:00.000",
                body.getValue().path("edges").path(0).path("properties")
                        .path("occurred_at").asText());
    }

    @Test
    void truncatesHugeGraphDatesToSupportedMillisecondPrecision() {
        assertEquals(
                "2026-07-26 07:30:55.903",
                HugeGraphProjectionClient.graphDate(
                        Instant.parse("2026-07-26T07:30:55.903463465Z")));
    }

    @Test
    void retriesRelationBatchesWhoseEndpointsHaveNotArrivedYet() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        HugeGraphProjectionClient client = client(properties(), http);
        stubSchema(http);
        when(http.exchange(eq("GET"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(
                        200, objectMapper.createObjectNode().put("id", "graph-object"), ""));
        when(http.requireSuccess(eq("PUT"), any(), any())).thenThrow(
                new ProjectionException("STORAGE_HTTP_400", "missing vertex", false));

        ProjectionException failure = assertThrows(
                ProjectionException.class,
                () -> client.applyBatch(List.of(validatedRelation())));

        assertEquals("GRAPH_RELATION_ENDPOINT_PENDING", failure.code());
        assertEquals(true, failure.retryable());
    }

    @Test
    void deletesHomogeneousObjectBatchesWithoutUsingGremlin() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        HugeGraphProjectionClient client = client(properties(), http);
        stubSchema(http);
        when(http.exchange(eq("GET"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(
                        200, objectMapper.createObjectNode().put("id", "graph-object"), ""));
        when(http.exchange(eq("DELETE"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(204, objectMapper.createObjectNode(), ""));

        assertEquals(List.of("graph-object"), client.applyBatch(List.of(validatedDeletion())));

        verify(http).exchange(eq("DELETE"), any(), eq(null));
        verify(http, never()).requireSuccess(eq("POST"), any(), any());
    }

    @Test
    void deletesEveryVertexBelongingToAnObjectType() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        HugeGraphProjectionClient client = client(properties(), http);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode vertices = response.putArray("vertices");
        vertices.addObject().put("id", "graph-object-1");
        vertices.addObject().put("id", "graph-object-2");
        ObjectNode vertexSchema = objectMapper.createObjectNode();
        vertexSchema.putArray("properties").add("object_id");
        ObjectNode relationSchemas = objectMapper.createObjectNode();
        relationSchemas.putArray("edgelabels");
        when(http.exchange(eq("GET"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(
                        200, vertexSchema, ""));
        when(http.requireSuccess(eq("GET"), any(), eq(null))).thenAnswer(invocation -> {
            String path = invocation.getArgument(1, java.net.URI.class).getPath();
            if (path.endsWith("/schema/vertexlabels/employee")) return vertexSchema;
            if (path.endsWith("/schema/edgelabels")) return relationSchemas;
            return response;
        });
        when(http.requireSuccess(eq("DELETE"), any(), eq(null))).thenReturn(
                objectMapper.createObjectNode());
        when(http.exchange(eq("DELETE"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(
                        204, objectMapper.createObjectNode(), ""));

        assertEquals(2, client.deleteObjectType(
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                "employee"));

        verify(http, times(2)).exchange(eq("DELETE"), any(), eq(null));
    }

    @Test
    void treatsAnObjectTypeWithoutAGraphSchemaAsAlreadyDeleted() {
        StorageHttpClient http = mock(StorageHttpClient.class);
        HugeGraphProjectionClient client = client(properties(), http);
        when(http.exchange(eq("GET"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(
                        404, objectMapper.createObjectNode(), ""));

        assertEquals(0, client.deleteObjectType(
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                "draft-object"));

        verify(http, never()).requireSuccess(eq("GET"), any(), eq(null));
        verify(http, never()).exchange(eq("DELETE"), any(), eq(null));
    }

    private HugeGraphProperties properties() {
        return new HugeGraphProperties("hugegraph", 8080);
    }

    private HugeGraphProjectionClient client(
            HugeGraphProperties properties, StorageHttpClient http) {
        ControlPlaneRepository repository = mock(ControlPlaneRepository.class);
        when(repository.objectSchema(any(UUID.class), any(String.class))).thenAnswer(invocation ->
                Optional.of(objectSchema(invocation.getArgument(1))));
        when(repository.relationSchemas(any(UUID.class))).thenReturn(List.of(new RelationSchema(
                "works_at", "employee", "company", "PIPELINE", null)));
        return new HugeGraphProjectionClient(properties, objectMapper, http, repository);
    }

    private ObjectSchema objectSchema(String typeId) {
        return new ObjectSchema(typeId, Map.of(
                "name", new PropertyContract("name", "STRING", false, true, false)));
    }

    private void stubSchema(StorageHttpClient http) {
        ObjectNode schema = objectMapper.createObjectNode().put("id", 1);
        ArrayNode properties = schema.putArray("properties");
        List.of(
                "correlation_id", "name", "object_id", "object_key", "object_type",
                "occurred_at", "ontology_id", "projection_sequence")
                .forEach(properties::add);
        when(http.requireSuccess(eq("GET"), any(), eq(null))).thenReturn(schema);
        when(http.exchange(eq("GET"), any(), eq(null))).thenReturn(
                new StorageHttpClient.Response(200, schema, ""));
    }

    private ValidatedEvent validated(String objectType, String objectId, String name) {
        OntologyEventEnvelope event = new OntologyEventEnvelope(
                UUID.randomUUID(),
                "object.upsert",
                1,
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                Instant.parse("2026-07-21T00:00:00Z"),
                "test",
                "correlation-" + objectId,
                null,
                null,
                objectType,
                objectId,
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
                1,
                event.payload(),
                false,
                false);
    }

    private ValidatedEvent validatedRelation() {
        OntologyEventEnvelope event = new OntologyEventEnvelope(
                UUID.randomUUID(),
                "relation.upsert",
                1,
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                Instant.parse("2026-07-21T00:00:00Z"),
                "test",
                "correlation-relation",
                null,
                null,
                null,
                null,
                "works_at",
                "R-1",
                "employee",
                "E-1",
                "company",
                "C-1",
                objectMapper.createObjectNode(),
                null);
        return new ValidatedEvent(event, "relation:works_at:R-1", 1L, event.payload(), false, true);
    }

    private ValidatedEvent validatedDeletion() {
        OntologyEventEnvelope event = new OntologyEventEnvelope(
                UUID.randomUUID(),
                "object.delete",
                1,
                UUID.fromString("00000000-0000-0000-0000-00000000a001"),
                Instant.parse("2026-07-21T00:00:00Z"),
                "test",
                "correlation-delete",
                null,
                null,
                "employee",
                "E-1",
                null,
                null,
                null,
                null,
                null,
                null,
                objectMapper.createObjectNode(),
                null);
        return new ValidatedEvent(
                event, "object:employee:E-1", 1L, event.payload(), true, false);
    }
}
