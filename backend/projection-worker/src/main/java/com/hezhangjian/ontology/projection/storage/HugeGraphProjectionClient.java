package com.hezhangjian.ontology.projection.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.config.ProjectionProperties;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HugeGraphProjectionClient {
    private static final int EDGE_BATCH_ID_BUDGET = 12_000;
    private static final String OBJECT_LABEL = "ontology_object";
    private static final String RELATION_LABEL = "ontology_relation";
    private static final String BATCH_SCRIPT = """
            def tx = graph.tx()
            def g = graph.traversal()
            def ids = []
            try {
              edits.each { edit ->
                if (edit.kind == 'object') {
                  def found = g.V().hasLabel('ontology_object').has('object_key', edit.key).tryNext()
                  if (edit.deleted) {
                    if (found.isPresent()) {
                      def vertex = found.get()
                      ids.add(vertex.id().toString())
                      vertex.remove()
                    } else {
                      ids.add('missing:' + edit.key)
                    }
                  } else {
                    def vertex = found.isPresent()
                        ? found.get()
                        : graph.addVertex(org.apache.tinkerpop.gremlin.structure.T.label, 'ontology_object',
                            'object_key', edit.key)
                    edit.properties.each { key, value ->
                      if (key != 'object_key') {
                        vertex.property(key, value)
                      }
                    }
                    ids.add(vertex.id().toString())
                  }
                } else {
                  def found = g.E().hasLabel('ontology_relation').has('relation_key', edit.key).tryNext()
                  if (edit.deleted) {
                    if (found.isPresent()) {
                      def edge = found.get()
                      ids.add(edge.id().toString())
                      edge.remove()
                    } else {
                      ids.add('missing:' + edit.key)
                    }
                  } else {
                    def source = g.V().hasLabel('ontology_object').has('object_key', edit.sourceKey).tryNext()
                    def target = g.V().hasLabel('ontology_object').has('object_key', edit.targetKey).tryNext()
                    if (!source.isPresent() || !target.isPresent()) {
                      throw new IllegalStateException('Relation endpoint object is not projected')
                    }
                    def edge = found.isPresent()
                        ? found.get()
                        : source.get().addEdge('ontology_relation', target.get(),
                            'relation_key', edit.properties.relation_key,
                            'relation_type', edit.properties.relation_type,
                            'relation_id', edit.properties.relation_id,
                            'relation_version', edit.properties.relation_version,
                            'ontology_revision', edit.properties.ontology_revision,
                            'payload_json', edit.properties.payload_json,
                            'correlation_id', edit.properties.correlation_id,
                            'occurred_at', edit.properties.occurred_at)
                    edit.properties.each { key, value ->
                      if (key != 'relation_key') {
                        edge.property(key, value)
                      }
                    }
                    ids.add(edge.id().toString())
                  }
                }
              }
              tx.commit()
              ids
            } catch (Throwable failure) {
              tx.rollback()
              throw failure
            }
            """;
    private final URI baseUri;
    private final URI gremlinUri;
    private final ObjectMapper objectMapper;
    private final StorageHttpClient http;
    private volatile String objectLabelId;

    public HugeGraphProjectionClient(
            ProjectionProperties properties,
            ObjectMapper objectMapper,
            StorageHttpClient http) {
        this.baseUri = properties.hugegraphUrl().resolve(
                "/graphspaces/DEFAULT/graphs/hugegraph/");
        this.gremlinUri = properties.hugegraphGremlinUrl().resolve("/gremlin");
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public void ensureSchema() {
        property("object_key", "TEXT");
        property("object_type", "TEXT");
        property("object_id", "TEXT");
        property("object_version", "LONG");
        property("ontology_revision", "LONG");
        property("payload_json", "TEXT");
        property("correlation_id", "TEXT");
        property("occurred_at", "TEXT");
        property("relation_key", "TEXT");
        property("relation_type", "TEXT");
        property("relation_id", "TEXT");
        property("relation_version", "LONG");

        if (!exists("schema/vertexlabels/" + OBJECT_LABEL)) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("name", OBJECT_LABEL);
            body.put("id_strategy", "PRIMARY_KEY");
            body.set("properties", strings(
                    "object_key", "object_type", "object_id", "object_version",
                    "ontology_revision", "payload_json", "correlation_id", "occurred_at"));
            body.set("primary_keys", strings("object_key"));
            body.set("nullable_keys", objectMapper.createArrayNode());
            body.put("enable_label_index", true);
            http.requireSuccess("POST", uri("schema/vertexlabels"), body);
        }
        objectLabelId = http.requireSuccess(
                        "GET", uri("schema/vertexlabels/" + OBJECT_LABEL), null)
                .path("id")
                .asText();
        if (objectLabelId.isBlank()) {
            throw new ProjectionException(
                    "GRAPH_SCHEMA_INVALID", "HugeGraph omitted the object label id", false);
        }
        if (!exists("schema/edgelabels/" + RELATION_LABEL)) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("name", RELATION_LABEL);
            body.put("source_label", OBJECT_LABEL);
            body.put("target_label", OBJECT_LABEL);
            body.put("frequency", "MULTIPLE");
            body.set("properties", strings(
                    "relation_key", "relation_type", "relation_id", "relation_version",
                    "ontology_revision", "payload_json", "correlation_id", "occurred_at"));
            body.set("sort_keys", strings("relation_key"));
            body.set("nullable_keys", objectMapper.createArrayNode());
            body.put("enable_label_index", true);
            http.requireSuccess("POST", uri("schema/edgelabels"), body);
        }
        index("ontologyRelationByKey", "EDGE_LABEL", RELATION_LABEL, "relation_key");
    }

    public String apply(ValidatedEvent validated) {
        if (validated.relation()) {
            return validated.deleted() ? deleteRelation(validated.event()) : upsertRelation(validated.event());
        }
        return validated.deleted() ? deleteObject(validated.event()) : upsertObject(validated.event());
    }

    public List<String> applyBatch(List<ValidatedEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        if (events.stream().allMatch(event -> !event.relation() && !event.deleted())) {
            return upsertObjectsBatch(events);
        }
        if (events.stream().allMatch(event -> event.relation() && !event.deleted())) {
            return upsertRelationsBatch(events);
        }
        ArrayNode edits = objectMapper.createArrayNode();
        events.forEach(event -> edits.add(batchEdit(event)));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("gremlin", BATCH_SCRIPT);
        body.put("language", "gremlin-groovy");
        body.set("bindings", objectMapper.createObjectNode().set("edits", edits));
        body.set("aliases", objectMapper.createObjectNode().put("graph", "DEFAULT-hugegraph"));
        JsonNode response = http.requireSuccess("POST", gremlinUri, body);
        JsonNode data = response.path("result").path("data");
        if (!data.isArray() || data.size() != events.size()) {
            throw new ProjectionException(
                    "GRAPH_RESPONSE_INVALID", "HugeGraph omitted batch element ids", true);
        }
        List<String> ids = new ArrayList<>();
        data.forEach(id -> ids.add(id.asText()));
        return List.copyOf(ids);
    }

    private List<String> upsertObjectsBatch(List<ValidatedEvent> events) {
        Map<String, ValidatedEvent> uniqueEvents = new LinkedHashMap<>();
        for (ValidatedEvent event : events) {
            uniqueEvents.put(
                    objectKey(event.event().objectType(), event.event().objectId()),
                    event);
        }
        ArrayNode vertices = objectMapper.createArrayNode();
        ObjectNode strategies = null;
        for (ValidatedEvent validated : uniqueEvents.values()) {
            ObjectNode properties = objectProperties(validated.event());
            ObjectNode vertex = objectMapper.createObjectNode();
            vertex.put("label", OBJECT_LABEL);
            vertex.set("properties", properties);
            vertices.add(vertex);
            if (strategies == null) {
                strategies = overrideStrategies(properties);
            }
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.set("vertices", vertices);
        body.set("update_strategies", strategies);
        body.put("create_if_not_exist", true);
        JsonNode response = http.requireSuccess("PUT", uri("graph/vertices/batch"), body);
        JsonNode stored = response.path("vertices");
        if (!stored.isArray() || stored.size() != uniqueEvents.size()) {
            throw new ProjectionException(
                    "GRAPH_RESPONSE_INVALID", "HugeGraph omitted batch vertex ids", true);
        }
        Map<String, String> idsByObjectKey = new LinkedHashMap<>();
        Iterator<String> fallbackKeys = uniqueEvents.keySet().iterator();
        for (JsonNode vertex : stored) {
            if (!vertex.hasNonNull("id")) {
                throw new ProjectionException(
                        "GRAPH_RESPONSE_INVALID", "HugeGraph omitted a batch vertex id", true);
            }
            String objectKey = vertex.path("properties").path("object_key").asText();
            idsByObjectKey.put(objectKey.isBlank() ? fallbackKeys.next() : objectKey,
                    vertex.path("id").asText());
        }
        List<String> ids = new ArrayList<>();
        for (ValidatedEvent event : events) {
            ids.add(idsByObjectKey.get(objectKey(
                    event.event().objectType(),
                    event.event().objectId())));
        }
        return List.copyOf(ids);
    }

    private List<String> upsertRelationsBatch(List<ValidatedEvent> events) {
        Map<String, ValidatedEvent> uniqueEvents = new LinkedHashMap<>();
        for (ValidatedEvent event : events) {
            uniqueEvents.put(relationKey(event.event().relationType(), event.event().relationId()), event);
        }
        Map<String, String> idsByRelationKey = new LinkedHashMap<>();
        List<ValidatedEvent> chunk = new ArrayList<>();
        int chunkIdSize = 0;
        for (ValidatedEvent event : uniqueEvents.values()) {
            int edgeIdSize = estimatedEdgeIdSize(event.event());
            if (!chunk.isEmpty() && chunkIdSize + edgeIdSize > EDGE_BATCH_ID_BUDGET) {
                idsByRelationKey.putAll(upsertRelationChunk(chunk));
                chunk.clear();
                chunkIdSize = 0;
            }
            chunk.add(event);
            chunkIdSize += edgeIdSize;
        }
        if (!chunk.isEmpty()) {
            idsByRelationKey.putAll(upsertRelationChunk(chunk));
        }
        List<String> ids = new ArrayList<>();
        for (ValidatedEvent event : events) {
            ids.add(idsByRelationKey.get(relationKey(
                    event.event().relationType(), event.event().relationId())));
        }
        return List.copyOf(ids);
    }

    private Map<String, String> upsertRelationChunk(List<ValidatedEvent> events) {
        ArrayNode edges = objectMapper.createArrayNode();
        ObjectNode strategies = null;
        for (ValidatedEvent validated : events) {
            OntologyEventEnvelope event = validated.event();
            ObjectNode properties = relationProperties(event);
            ObjectNode edge = objectMapper.createObjectNode();
            edge.put("label", RELATION_LABEL);
            edge.put("outV", objectLabelId + ":" + objectKey(event.sourceObjectType(), event.sourceObjectId()));
            edge.put("inV", objectLabelId + ":" + objectKey(event.targetObjectType(), event.targetObjectId()));
            edge.put("outVLabel", OBJECT_LABEL);
            edge.put("inVLabel", OBJECT_LABEL);
            edge.set("properties", properties);
            edges.add(edge);
            if (strategies == null) {
                strategies = overrideStrategies(properties);
            }
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.set("edges", edges);
        body.set("update_strategies", strategies);
        body.put("check_vertex", true);
        body.put("create_if_not_exist", true);
        JsonNode response;
        try {
            response = http.requireSuccess("PUT", uri("graph/edges/batch"), body);
        } catch (ProjectionException exception) {
            if ("STORAGE_HTTP_400".equals(exception.code())) {
                throw new ProjectionException(
                        "GRAPH_RELATION_ENDPOINT_PENDING",
                        "A relation batch arrived before all endpoint objects were available",
                        true,
                        exception);
            }
            throw exception;
        }
        JsonNode stored = response.path("edges");
        if (!stored.isArray() || stored.size() != events.size()) {
            throw new ProjectionException(
                    "GRAPH_RESPONSE_INVALID", "HugeGraph omitted batch edge ids", true);
        }
        Map<String, String> idsByRelationKey = new LinkedHashMap<>();
        Iterator<String> fallbackKeys = events.stream()
                .map(event -> relationKey(event.event().relationType(), event.event().relationId()))
                .iterator();
        for (JsonNode edge : stored) {
            if (!edge.hasNonNull("id")) {
                throw new ProjectionException(
                        "GRAPH_RESPONSE_INVALID", "HugeGraph omitted a batch edge id", true);
            }
            String relationKey = edge.path("properties").path("relation_key").asText();
            idsByRelationKey.put(relationKey.isBlank() ? fallbackKeys.next() : relationKey,
                    edge.path("id").asText());
        }
        return idsByRelationKey;
    }

    private int estimatedEdgeIdSize(OntologyEventEnvelope event) {
        return String.valueOf(objectLabelId).length() * 2
                + objectKey(event.sourceObjectType(), event.sourceObjectId()).length()
                + objectKey(event.targetObjectType(), event.targetObjectId()).length()
                + relationKey(event.relationType(), event.relationId()).length()
                + 24;
    }

    public List<GraphObject> listObjects() {
        List<GraphObject> result = new ArrayList<>();
        String page = "";
        do {
            String suffix = "graph/vertices?limit=500&page=" + encode(page);
            JsonNode response = http.requireSuccess("GET", uri(suffix), null);
            for (JsonNode vertex : response.path("vertices")) {
                if (OBJECT_LABEL.equals(vertex.path("label").asText())) {
                    JsonNode properties = vertex.path("properties");
                    result.add(new GraphObject(
                            vertex.path("id").asText(),
                            properties.path("object_type").asText(),
                            properties.path("object_id").asText(),
                            properties.path("object_version").asLong(),
                            properties.path("ontology_revision").asLong(),
                            readPayload(properties.path("payload_json").asText()),
                            properties.path("correlation_id").asText(),
                            properties.path("occurred_at").asText()));
                }
            }
            page = response.path("page").isTextual() ? response.path("page").asText() : null;
        } while (page != null && !page.isBlank());
        return result;
    }

    private String upsertObject(OntologyEventEnvelope event) {
        ObjectNode properties = objectProperties(event);
        ObjectNode vertex = objectMapper.createObjectNode();
        vertex.put("label", OBJECT_LABEL);
        vertex.set("properties", properties);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("vertices", objectMapper.createArrayNode().add(vertex));
        body.set("update_strategies", overrideStrategies(properties));
        body.put("create_if_not_exist", true);
        JsonNode response = http.requireSuccess("PUT", uri("graph/vertices/batch"), body);
        JsonNode stored = response.path("vertices").path(0);
        if (!stored.hasNonNull("id")) {
            throw new ProjectionException("GRAPH_RESPONSE_INVALID", "HugeGraph omitted the vertex id", true);
        }
        return stored.path("id").asText();
    }

    private ObjectNode objectProperties(OntologyEventEnvelope event) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("object_key", objectKey(event.objectType(), event.objectId()));
        properties.put("object_type", event.objectType());
        properties.put("object_id", event.objectId());
        properties.put("object_version", event.objectVersion());
        properties.put("ontology_revision", event.ontologyRevision());
        properties.put("payload_json", event.payload() == null ? "{}" : event.payload().toString());
        properties.put("correlation_id", event.correlationId());
        properties.put("occurred_at", event.occurredAt().toString());
        return properties;
    }

    private String deleteObject(OntologyEventEnvelope event) {
        String id = findObjectId(event.objectType(), event.objectId());
        if (id == null) {
            return "missing:" + objectKey(event.objectType(), event.objectId());
        }
        StorageHttpClient.Response response = http.exchange(
                "DELETE", uri("graph/vertices/" + encodeJsonString(id) + "?label=" + OBJECT_LABEL), null);
        if (response.status() != 204 && response.status() != 404) {
            throw storageFailure(response.status(), "delete vertex");
        }
        return id;
    }

    private String upsertRelation(OntologyEventEnvelope event) {
        String sourceId = findObjectId(event.sourceObjectType(), event.sourceObjectId());
        String targetId = findObjectId(event.targetObjectType(), event.targetObjectId());
        if (sourceId == null || targetId == null) {
            throw new ProjectionException("RELATION_ENDPOINT_MISSING", "Relation endpoint object is not projected", true);
        }
        ObjectNode properties = relationProperties(event);
        ObjectNode edge = objectMapper.createObjectNode();
        edge.put("label", RELATION_LABEL);
        edge.put("outV", sourceId);
        edge.put("inV", targetId);
        edge.put("outVLabel", OBJECT_LABEL);
        edge.put("inVLabel", OBJECT_LABEL);
        edge.set("properties", properties);
        ObjectNode body = objectMapper.createObjectNode();
        body.set("edges", objectMapper.createArrayNode().add(edge));
        body.set("update_strategies", overrideStrategies(properties));
        body.put("check_vertex", true);
        body.put("create_if_not_exist", true);
        JsonNode response = http.requireSuccess("PUT", uri("graph/edges/batch"), body);
        JsonNode stored = response.path("edges").path(0);
        if (!stored.hasNonNull("id")) {
            throw new ProjectionException("GRAPH_RESPONSE_INVALID", "HugeGraph omitted the edge id", true);
        }
        return stored.path("id").asText();
    }

    private String deleteRelation(OntologyEventEnvelope event) {
        String id = findElementId("edges", "relation_key", relationKey(event.relationType(), event.relationId()));
        if (id == null) {
            return "missing:" + relationKey(event.relationType(), event.relationId());
        }
        StorageHttpClient.Response response = http.exchange(
                "DELETE", uri("graph/edges/" + encode(id) + "?label=" + RELATION_LABEL), null);
        if (response.status() != 204 && response.status() != 404) {
            throw storageFailure(response.status(), "delete edge");
        }
        return id;
    }

    private String findObjectId(String type, String id) {
        String candidate = objectLabelId + ":" + objectKey(type, id);
        StorageHttpClient.Response response = http.exchange(
                "GET", uri("graph/vertices/" + encodeJsonString(candidate) + "?label=" + OBJECT_LABEL), null);
        if (response.status() == 404) {
            return null;
        }
        if (response.status() != 200) {
            throw storageFailure(response.status(), "inspect vertex");
        }
        return response.json().path("id").asText(candidate);
    }

    private String findElementId(String collection, String property, String value) {
        ObjectNode condition = objectMapper.createObjectNode().put(property, value);
        String path = "graph/" + collection + "?properties=" + encode(condition.toString()) + "&limit=1";
        JsonNode response = http.requireSuccess("GET", uri(path), null);
        JsonNode first = response.path(collection).path(0);
        return first.hasNonNull("id") ? first.path("id").asText() : null;
    }

    private ObjectNode relationProperties(OntologyEventEnvelope event) {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("relation_key", relationKey(event.relationType(), event.relationId()));
        properties.put("relation_type", event.relationType());
        properties.put("relation_id", event.relationId());
        properties.put("relation_version", event.relationVersion());
        properties.put("ontology_revision", event.ontologyRevision());
        properties.put("payload_json", event.payload() == null ? "{}" : event.payload().toString());
        properties.put("correlation_id", event.correlationId());
        properties.put("occurred_at", event.occurredAt().toString());
        return properties;
    }

    private ObjectNode batchEdit(ValidatedEvent validated) {
        OntologyEventEnvelope event = validated.event();
        ObjectNode edit = objectMapper.createObjectNode();
        edit.put("deleted", validated.deleted());
        if (validated.relation()) {
            edit.put("kind", "relation");
            edit.put("key", relationKey(event.relationType(), event.relationId()));
            edit.put("sourceKey", objectKey(event.sourceObjectType(), event.sourceObjectId()));
            edit.put("targetKey", objectKey(event.targetObjectType(), event.targetObjectId()));
            edit.set("properties", relationProperties(event));
        } else {
            edit.put("kind", "object");
            edit.put("key", objectKey(event.objectType(), event.objectId()));
            edit.set("properties", objectProperties(event));
        }
        return edit;
    }

    private ObjectNode overrideStrategies(ObjectNode properties) {
        ObjectNode strategies = objectMapper.createObjectNode();
        properties.fieldNames().forEachRemaining(field -> strategies.put(field, "OVERRIDE"));
        return strategies;
    }

    private void property(String name, String type) {
        if (exists("schema/propertykeys/" + name)) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("data_type", type);
        body.put("cardinality", "SINGLE");
        http.requireSuccess("POST", uri("schema/propertykeys"), body);
    }

    private void index(String name, String baseType, String baseValue, String field) {
        if (exists("schema/indexlabels/" + name)) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("base_type", baseType);
        body.put("base_value", baseValue);
        body.put("index_type", "SECONDARY");
        body.set("fields", strings(field));
        http.requireSuccess("POST", uri("schema/indexlabels"), body);
    }

    private boolean exists(String path) {
        int status = http.exchange("GET", uri(path), null).status();
        if (status == 200) {
            return true;
        }
        if (status == 404) {
            return false;
        }
        throw new ProjectionException("GRAPH_SCHEMA_UNAVAILABLE", "Cannot inspect HugeGraph schema", true);
    }

    private ArrayNode strings(String... values) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private JsonNode readPayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception exception) {
            throw new ProjectionException("GRAPH_PAYLOAD_INVALID", "Stored graph payload is invalid", false, exception);
        }
    }

    private String objectKey(String type, String id) {
        return stableKey(type, id);
    }

    private String relationKey(String type, String id) {
        return stableKey(type, id);
    }

    private String stableKey(String type, String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((type + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
    }

    private String encodeJsonString(String value) {
        try {
            return encode(objectMapper.writeValueAsString(value));
        } catch (Exception exception) {
            throw new ProjectionException("GRAPH_ID_INVALID", "Cannot encode graph id", false, exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI uri(String path) {
        return baseUri.resolve(path);
    }

    private ProjectionException storageFailure(int status, String operation) {
        return new ProjectionException(
                "GRAPH_HTTP_" + status,
                "HugeGraph failed to " + operation + " with HTTP " + status,
                status == 408 || status == 429 || status >= 500);
    }

    public record GraphObject(
            String graphId,
            String objectType,
            String objectId,
            long objectVersion,
            long ontologyRevision,
            JsonNode payload,
            String correlationId,
            String occurredAt) {
    }
}
