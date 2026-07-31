package com.hezhangjian.ontology.projection.storage;

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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Projects ontology instances into HugeGraph's native schema.
 *
 * <p>Each published object type owns a vertex label and each published link type owns an edge
 * label. Stable physical property keys are stored as native HugeGraph properties. The old
 * ontology_object/ontology_relation envelope is intentionally not used.</p>
 */
@Component
public class HugeGraphProjectionClient {
    private static final DateTimeFormatter HUGEGRAPH_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneOffset.UTC);
    private static final Set<String> OBJECT_SYSTEM_PROPERTIES = Set.of(
            "object_key", "ontology_id", "object_type", "object_id", "projection_sequence",
            "correlation_id", "occurred_at");
    private static final Set<String> RELATION_SYSTEM_PROPERTIES = Set.of(
            "relation_key", "ontology_id", "relation_type", "relation_id",
            "source_object_type", "source_object_id", "target_object_type", "target_object_id",
            "projection_sequence", "correlation_id", "occurred_at");
    private static final String LEGACY_OBJECT_LABEL = "ontology_object";
    private static final String LEGACY_RELATION_LABEL = "ontology_relation";

    private final URI baseUri;
    private final ControlPlaneRepository repository;
    private final ObjectMapper objectMapper;
    private final StorageHttpClient http;
    private final Map<String, String> labelIds = new ConcurrentHashMap<>();

    public HugeGraphProjectionClient(
            HugeGraphProperties properties,
            ObjectMapper objectMapper,
            StorageHttpClient http,
            ControlPlaneRepository repository) {
        this.baseUri = properties.url().resolve(
                "/graphspaces/DEFAULT/graphs/hugegraph/");
        this.objectMapper = objectMapper;
        this.http = http;
        this.repository = repository;
    }

    public void ensureSchema() {
        property("object_key", "TEXT", "SINGLE");
        property("ontology_id", "TEXT", "SINGLE");
        property("object_type", "TEXT", "SINGLE");
        property("object_id", "TEXT", "SINGLE");
        property("projection_sequence", "LONG", "SINGLE");
        property("correlation_id", "TEXT", "SINGLE");
        property("occurred_at", "DATE", "SINGLE");
        property("relation_key", "TEXT", "SINGLE");
        property("relation_type", "TEXT", "SINGLE");
        property("relation_id", "TEXT", "SINGLE");
        property("source_object_type", "TEXT", "SINGLE");
        property("source_object_id", "TEXT", "SINGLE");
        property("target_object_type", "TEXT", "SINGLE");
        property("target_object_id", "TEXT", "SINGLE");
    }

    public String reconcileSchema(UUID ontologyId) {
        ensureSchema();
        List<ObjectSchema> objects = repository.objectSchemas(ontologyId);
        objects.forEach(schema -> ensureObjectSchema(ontologyId, schema));
        List<RelationSchema> relations = repository.relationSchemas(ontologyId);
        relations.forEach(schema -> ensureRelationSchema(ontologyId, schema));
        return objects.size() + " vertex labels / " + relations.size() + " edge labels";
    }

    public String apply(ValidatedEvent validated) {
        if (validated.relation()) {
            return validated.deleted() ? deleteRelation(validated.event()) : upsertRelation(validated);
        }
        return validated.deleted() ? deleteObject(validated.event()) : upsertObject(validated);
    }

    public List<String> applyBatch(List<ValidatedEvent> events) {
        if (events.isEmpty()) return List.of();
        if (events.stream().allMatch(event -> !event.relation() && !event.deleted())) {
            return upsertObjectsBatch(events);
        }
        if (events.stream().allMatch(event -> event.relation() && !event.deleted())) {
            return upsertRelationsBatch(events);
        }
        return events.stream().map(this::apply).toList();
    }

    public synchronized int deleteObjectType(UUID ontologyId, String objectType) {
        String vertexSchemaPath = "vertexlabels/" + objectType;
        if (!exists("schema/" + vertexSchemaPath)) return 0;
        JsonNode vertexSchema = http.requireSuccess(
                "GET", uri("schema/" + vertexSchemaPath), null);
        List<String> domainProperties = new ArrayList<>();
        vertexSchema.path("properties").forEach(property -> {
            if (!OBJECT_SYSTEM_PROPERTIES.contains(property.asText())) {
                domainProperties.add(property.asText());
            }
        });
        domainProperties.sort(String::compareTo);

        String filters = "label=" + encode(objectType);
        List<JsonNode> vertices = all("vertices", filters);
        for (JsonNode vertex : vertices) {
            String id = vertex.path("id").asText();
            if (id.isBlank()) {
                throw new ProjectionException(
                        "GRAPH_RESPONSE_INVALID",
                        "HugeGraph omitted a vertex id while deleting object type " + objectType,
                        true);
            }
            StorageHttpClient.Response response = http.exchange(
                    "DELETE",
                    uri("graph/vertices/" + encodeJsonString(id)
                            + "?label=" + encode(objectType)),
                    null);
            if (response.status() != 204 && response.status() != 404) {
                throw storageFailure(response.status(), "delete object type vertex");
            }
        }
        deleteIncidentRelationSchemas(objectType);
        deleteSchema(vertexSchemaPath);
        labelIds.remove(objectType);
        for (String property : domainProperties) {
            if (exists("schema/propertykeys/" + property)) {
                deleteSchema("propertykeys/" + property);
            }
        }
        return vertices.size();
    }

    public synchronized void deleteRelationType(String relationType) {
        if (exists("schema/edgelabels/" + relationType)) {
            deleteSchema("edgelabels/" + relationType);
        }
    }

    private void deleteIncidentRelationSchemas(String objectType) {
        List<String> relationTypes = new ArrayList<>();
        JsonNode response = http.requireSuccess(
                "GET", uri("schema/edgelabels"), null);
        response.path("edgelabels").forEach(label -> {
            if (objectType.equals(label.path("source_label").asText())
                    || objectType.equals(label.path("target_label").asText())) {
                relationTypes.add(label.path("name").asText());
            }
        });
        relationTypes.stream()
                .filter(name -> !name.isBlank())
                .sorted()
                .forEach(name -> deleteSchema("edgelabels/" + name));
    }

    private List<String> upsertObjectsBatch(List<ValidatedEvent> events) {
        Map<String, List<ValidatedEvent>> byType = new LinkedHashMap<>();
        events.forEach(event -> byType.computeIfAbsent(
                event.event().objectType(), ignored -> new ArrayList<>()).add(event));
        Map<UUID, String> ids = new LinkedHashMap<>();
        byType.values().forEach(group -> ids.putAll(upsertObjectTypeBatch(group)));
        return events.stream().map(event -> ids.get(event.event().eventId())).toList();
    }

    private Map<UUID, String> upsertObjectTypeBatch(List<ValidatedEvent> events) {
        ValidatedEvent sample = events.getFirst();
        OntologyEventEnvelope first = sample.event();
        ObjectSchema schema = requireObjectSchema(first.ontologyId(), first.objectType());
        ensureObjectSchema(first.ontologyId(), schema);

        Map<String, ValidatedEvent> unique = new LinkedHashMap<>();
        events.forEach(event -> unique.put(objectKey(
                event.event().ontologyId(), event.event().objectType(), event.event().objectId()), event));
        ArrayNode vertices = objectMapper.createArrayNode();
        unique.values().forEach(event -> {
            ObjectNode vertex = vertices.addObject();
            vertex.put("label", event.event().objectType());
            vertex.set("properties", objectProperties(event));
        });
        ObjectNode body = objectMapper.createObjectNode();
        body.set("vertices", vertices);
        body.set("update_strategies", overrideStrategies(vertices.path(0).path("properties")));
        body.put("create_if_not_exist", true);
        JsonNode response = http.requireSuccess("PUT", uri("graph/vertices/batch"), body);
        JsonNode stored = response.path("vertices");
        if (!stored.isArray() || stored.size() != unique.size()) {
            throw new ProjectionException(
                    "GRAPH_RESPONSE_INVALID", "HugeGraph omitted batch vertex ids", true);
        }
        Map<String, String> idsByKey = new LinkedHashMap<>();
        Iterator<String> fallback = unique.keySet().iterator();
        for (JsonNode vertex : stored) {
            if (!vertex.hasNonNull("id")) {
                throw new ProjectionException(
                        "GRAPH_RESPONSE_INVALID", "HugeGraph omitted a batch vertex id", true);
            }
            String key = vertex.path("properties").path("object_key").asText();
            idsByKey.put(key.isBlank() ? fallback.next() : key, vertex.path("id").asText());
        }
        Map<UUID, String> result = new LinkedHashMap<>();
        events.forEach(event -> result.put(event.event().eventId(), idsByKey.get(objectKey(
                event.event().ontologyId(), event.event().objectType(), event.event().objectId()))));
        return result;
    }

    private List<String> upsertRelationsBatch(List<ValidatedEvent> events) {
        List<String> result = new ArrayList<>();
        for (ValidatedEvent event : events) result.add(upsertRelation(event));
        return List.copyOf(result);
    }

    private String upsertObject(ValidatedEvent validated) {
        return upsertObjectTypeBatch(List.of(validated)).get(validated.event().eventId());
    }

    private String upsertRelation(ValidatedEvent validated) {
        OntologyEventEnvelope event = validated.event();
        RelationSchema schema = repository.relationSchemas(event.ontologyId()).stream()
                .filter(value -> value.typeId().equals(event.relationType()))
                .findFirst()
                .orElseThrow(() -> new ProjectionException(
                        "GRAPH_SCHEMA_INVALID", "Unknown relation schema " + event.relationType(), false));
        ensureRelationSchema(event.ontologyId(), schema);
        String sourceId = findObjectId(
                event.ontologyId(), event.sourceObjectType(), event.sourceObjectId());
        String targetId = findObjectId(
                event.ontologyId(), event.targetObjectType(), event.targetObjectId());
        if (sourceId == null || targetId == null) {
            throw new ProjectionException(
                    "GRAPH_RELATION_ENDPOINT_PENDING",
                    "A relation arrived before all endpoint objects were projected",
                    true);
        }
        ObjectNode edge = objectMapper.createObjectNode();
        edge.put("label", event.relationType());
        edge.put("outV", sourceId);
        edge.put("inV", targetId);
        edge.put("outVLabel", event.sourceObjectType());
        edge.put("inVLabel", event.targetObjectType());
        edge.set("properties", relationProperties(validated));
        ObjectNode body = objectMapper.createObjectNode();
        body.set("edges", objectMapper.createArrayNode().add(edge));
        body.set("update_strategies", overrideStrategies(edge.path("properties")));
        body.put("check_vertex", true);
        body.put("create_if_not_exist", true);
        JsonNode stored;
        try {
            stored = http.requireSuccess("PUT", uri("graph/edges/batch"), body)
                    .path("edges").path(0);
        } catch (ProjectionException failure) {
            if ("STORAGE_HTTP_400".equals(failure.code())) {
                throw new ProjectionException(
                        "GRAPH_RELATION_ENDPOINT_PENDING",
                        "HugeGraph could not resolve a relation endpoint",
                        true,
                        failure);
            }
            throw failure;
        }
        if (!stored.hasNonNull("id")) {
            throw new ProjectionException(
                    "GRAPH_RESPONSE_INVALID", "HugeGraph omitted the edge id", true);
        }
        return stored.path("id").asText();
    }

    private String deleteObject(OntologyEventEnvelope event) {
        String id = findObjectId(event.ontologyId(), event.objectType(), event.objectId());
        if (id == null) return "missing:" + objectKey(
                event.ontologyId(), event.objectType(), event.objectId());
        StorageHttpClient.Response response = http.exchange(
                "DELETE",
                uri("graph/vertices/" + encodeJsonString(id) + "?label=" + encode(event.objectType())),
                null);
        if (response.status() != 204 && response.status() != 404) {
            throw storageFailure(response.status(), "delete vertex");
        }
        return id;
    }

    private String deleteRelation(OntologyEventEnvelope event) {
        String id = findElementId(
                "edges", "relation_key",
                relationKey(event.ontologyId(), event.relationType(), event.relationId()),
                event.relationType());
        if (id == null) return "missing:" + relationKey(
                event.ontologyId(), event.relationType(), event.relationId());
        StorageHttpClient.Response response = http.exchange(
                "DELETE",
                uri("graph/edges/" + encode(id) + "?label=" + encode(event.relationType())),
                null);
        if (response.status() != 204 && response.status() != 404) {
            throw storageFailure(response.status(), "delete edge");
        }
        return id;
    }

    public List<GraphObject> listObjects() {
        List<GraphObject> result = new ArrayList<>();
        for (JsonNode vertex : all("vertices")) {
            String label = vertex.path("label").asText();
            if (!label.startsWith("ot_")) continue;
            JsonNode properties = vertex.path("properties");
            result.add(new GraphObject(
                    vertex.path("id").asText(),
                    properties.path("ontology_id").asText(),
                    properties.path("object_type").asText(label),
                    properties.path("object_id").asText(),
                    properties.path("projection_sequence").asLong(),
                    payload(properties, OBJECT_SYSTEM_PROPERTIES),
                    properties.path("correlation_id").asText(),
                    properties.path("occurred_at").asText()));
        }
        return List.copyOf(result);
    }

    public List<GraphRelation> listRelations() {
        List<GraphRelation> result = new ArrayList<>();
        for (JsonNode edge : all("edges")) {
            String label = edge.path("label").asText();
            if (!label.startsWith("lt_")) continue;
            JsonNode properties = edge.path("properties");
            result.add(new GraphRelation(
                    edge.path("id").asText(),
                    properties.path("ontology_id").asText(),
                    properties.path("relation_type").asText(label),
                    properties.path("relation_id").asText(),
                    properties.path("source_object_type").asText(),
                    properties.path("source_object_id").asText(),
                    properties.path("target_object_type").asText(),
                    properties.path("target_object_id").asText(),
                    properties.path("projection_sequence").asLong(),
                    payload(properties, RELATION_SYSTEM_PROPERTIES),
                    properties.path("correlation_id").asText(),
                    properties.path("occurred_at").asText()));
        }
        return List.copyOf(result);
    }

    private List<JsonNode> all(String collection) {
        return all(collection, "");
    }

    private List<JsonNode> all(String collection, String filters) {
        List<JsonNode> values = new ArrayList<>();
        String page = "";
        do {
            String query = "limit=500&page=" + encode(page);
            if (!filters.isBlank()) query = filters + "&" + query;
            JsonNode response = http.requireSuccess(
                    "GET", uri("graph/" + collection + "?" + query), null);
            response.path(collection).forEach(values::add);
            page = response.path("page").isTextual() ? response.path("page").asText() : null;
        } while (page != null && !page.isBlank());
        return values;
    }

    public synchronized GraphSchemaRebuildResult rebuildSchemaWithoutLegacyVersions() {
        return rebuildSchemaWithoutLegacyVersions(List.of(), List.of());
    }

    public synchronized GraphSchemaRebuildResult rebuildSchemaWithoutLegacyVersions(
            List<GraphObject> sourceObjects, List<GraphRelation> sourceRelations) {
        removeLegacySchema();
        Set<UUID> ontologyIds = new LinkedHashSet<>();
        sourceObjects.forEach(object -> ontologyIds.add(UUID.fromString(object.ontologyId())));
        sourceRelations.forEach(relation -> ontologyIds.add(UUID.fromString(relation.ontologyId())));
        ontologyIds.addAll(repository.ontologyIds());
        ontologyIds.forEach(this::reconcileSchema);
        List<GraphObject> currentObjects = sourceObjects.stream()
                .filter(object -> repository.objectSchema(
                        UUID.fromString(object.ontologyId()), object.objectType()).isPresent())
                .toList();
        List<GraphRelation> currentRelations = sourceRelations.stream()
                .filter(relation -> repository.relationSchemas(
                                UUID.fromString(relation.ontologyId())).stream()
                        .anyMatch(schema -> schema.typeId().equals(relation.relationType())))
                .toList();
        currentObjects.forEach(object -> apply(restoreObject(object)));
        currentRelations.forEach(relation -> apply(restoreRelation(relation)));
        return new GraphSchemaRebuildResult(
                currentObjects.size(),
                currentRelations.size(),
                List.of(
                        "native_properties",
                        "projection_sequence",
                        "skipped_stale_objects:"
                                + (sourceObjects.size() - currentObjects.size()),
                        "skipped_stale_relations:"
                                + (sourceRelations.size() - currentRelations.size())));
    }

    private void removeLegacySchema() {
        if (exists("schema/indexlabels/ontologyRelationByKey")) {
            deleteSchema("indexlabels/ontologyRelationByKey");
        }
        if (exists("schema/edgelabels/" + LEGACY_RELATION_LABEL)) {
            deleteSchema("edgelabels/" + LEGACY_RELATION_LABEL);
        }
        if (exists("schema/vertexlabels/" + LEGACY_OBJECT_LABEL)) {
            deleteSchema("vertexlabels/" + LEGACY_OBJECT_LABEL);
        }
        if (exists("schema/propertykeys/payload_json")) {
            deleteSchema("propertykeys/payload_json");
        }
    }

    private ValidatedEvent restoreObject(GraphObject object) {
        OntologyEventEnvelope event = new OntologyEventEnvelope(
                UUID.randomUUID(), "object.upsert", 1, UUID.fromString(object.ontologyId()),
                instant(object.occurredAt()), "graph-schema-rebuild",
                object.correlationId(), null, null, object.objectType(), object.objectId(),
                null, null, null, null, null, null, object.payload(), null);
        return new ValidatedEvent(event, "object:" + object.objectType() + ":" + object.objectId(),
                object.projectionSequence(), object.payload(), false, false);
    }

    private ValidatedEvent restoreRelation(GraphRelation relation) {
        OntologyEventEnvelope event = new OntologyEventEnvelope(
                UUID.randomUUID(), "relation.upsert", 1, UUID.fromString(relation.ontologyId()),
                instant(relation.occurredAt()), "graph-schema-rebuild",
                relation.correlationId(), null, null, null, null, relation.relationType(),
                relation.relationId(), relation.sourceObjectType(), relation.sourceObjectId(),
                relation.targetObjectType(), relation.targetObjectId(), relation.payload(), null);
        return new ValidatedEvent(event, "relation:" + relation.relationType() + ":"
                + relation.relationId(), relation.projectionSequence(), relation.payload(), false, true);
    }

    private ObjectSchema requireObjectSchema(UUID ontologyId, String type) {
        return repository.objectSchema(ontologyId, type)
                .orElseThrow(() -> new ProjectionException(
                        "GRAPH_SCHEMA_INVALID", "Unknown object schema " + type, false));
    }

    private void ensureObjectSchema(UUID ontologyId, ObjectSchema schema) {
        ensureSchema();
        schema.properties().values().forEach(this::ensureDomainProperty);
        if (exists("schema/vertexlabels/" + schema.typeId())) {
            appendMissingProperties("vertexlabels", schema.typeId(), schema.properties().keySet());
            labelId(schema.typeId());
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", schema.typeId());
        body.put("id_strategy", "PRIMARY_KEY");
        LinkedHashSet<String> properties = new LinkedHashSet<>(OBJECT_SYSTEM_PROPERTIES);
        properties.addAll(schema.properties().keySet());
        body.set("properties", strings(properties));
        body.set("primary_keys", strings(List.of("object_key")));
        LinkedHashSet<String> nullable = new LinkedHashSet<>(schema.properties().keySet());
        schema.properties().values().stream()
                .filter(PropertyContract::required)
                .map(PropertyContract::propertyId)
                .forEach(nullable::remove);
        body.set("nullable_keys", strings(nullable));
        body.put("enable_label_index", true);
        http.requireSuccess("POST", uri("schema/vertexlabels"), body);
        labelId(schema.typeId());
    }

    private void ensureRelationSchema(UUID ontologyId, RelationSchema schema) {
        ensureObjectSchema(ontologyId, requireObjectSchema(ontologyId, schema.sourceTypeId()));
        ensureObjectSchema(ontologyId, requireObjectSchema(ontologyId, schema.targetTypeId()));
        if (exists("schema/edgelabels/" + schema.typeId())) return;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", schema.typeId());
        body.put("source_label", schema.sourceTypeId());
        body.put("target_label", schema.targetTypeId());
        body.put("frequency", "MULTIPLE");
        body.set("properties", strings(RELATION_SYSTEM_PROPERTIES));
        body.set("sort_keys", strings(List.of("relation_key")));
        body.set("nullable_keys", objectMapper.createArrayNode());
        body.put("enable_label_index", true);
        http.requireSuccess("POST", uri("schema/edgelabels"), body);
    }

    private void ensureDomainProperty(PropertyContract property) {
        String cardinality = property.valueType().endsWith("_ARRAY") ? "LIST" : "SINGLE";
        property(property.propertyId(), graphType(property.valueType()), cardinality);
    }

    private String graphType(String valueType) {
        return switch (valueType) {
            case "BOOLEAN" -> "BOOLEAN";
            case "DECIMAL" -> "DOUBLE";
            case "INTEGER" -> "INT";
            case "LONG" -> "LONG";
            case "DATE", "DATETIME" -> "DATE";
            default -> "TEXT";
        };
    }

    private void appendMissingProperties(
            String collection, String label, Set<String> expectedProperties) {
        JsonNode current = http.requireSuccess(
                "GET", uri("schema/" + collection + "/" + label), null);
        Set<String> existing = new LinkedHashSet<>();
        current.path("properties").forEach(value -> existing.add(value.asText()));
        LinkedHashSet<String> missing = new LinkedHashSet<>(expectedProperties);
        missing.removeAll(existing);
        if (missing.isEmpty()) return;
        ObjectNode body = objectMapper.createObjectNode().put("name", label);
        body.set("properties", strings(missing));
        body.set("nullable_keys", strings(missing));
        http.requireSuccess(
                "PUT", uri("schema/" + collection + "/" + label + "?action=append"), body);
    }

    private ObjectNode objectProperties(ValidatedEvent validated) {
        OntologyEventEnvelope event = validated.event();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("object_key", objectKey(event.ontologyId(), event.objectType(), event.objectId()));
        properties.put("ontology_id", event.ontologyId().toString());
        properties.put("object_type", event.objectType());
        properties.put("object_id", event.objectId());
        properties.put("projection_sequence", validated.projectionSequence());
        properties.put("correlation_id", event.correlationId());
        properties.put("occurred_at", graphDate(event.occurredAt()));
        if (event.payload() != null && event.payload().isObject()) {
            event.payload().properties().forEach(entry -> properties.set(entry.getKey(), entry.getValue()));
        }
        return properties;
    }

    private ObjectNode relationProperties(ValidatedEvent validated) {
        OntologyEventEnvelope event = validated.event();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.put("relation_key", relationKey(
                event.ontologyId(), event.relationType(), event.relationId()));
        properties.put("ontology_id", event.ontologyId().toString());
        properties.put("relation_type", event.relationType());
        properties.put("relation_id", event.relationId());
        properties.put("source_object_type", event.sourceObjectType());
        properties.put("source_object_id", event.sourceObjectId());
        properties.put("target_object_type", event.targetObjectType());
        properties.put("target_object_id", event.targetObjectId());
        properties.put("projection_sequence", validated.projectionSequence());
        properties.put("correlation_id", event.correlationId());
        properties.put("occurred_at", graphDate(event.occurredAt()));
        if (event.payload() != null && event.payload().isObject()) {
            event.payload().properties().forEach(entry -> properties.set(entry.getKey(), entry.getValue()));
        }
        return properties;
    }

    private JsonNode payload(JsonNode properties, Set<String> systemProperties) {
        ObjectNode payload = objectMapper.createObjectNode();
        properties.properties().forEach(entry -> {
            if (!systemProperties.contains(entry.getKey())) {
                payload.set(entry.getKey(), entry.getValue());
            }
        });
        return payload;
    }

    private String findObjectId(UUID ontologyId, String type, String id) {
        String candidate = labelId(type) + ":" + objectKey(ontologyId, type, id);
        StorageHttpClient.Response response = http.exchange(
                "GET",
                uri("graph/vertices/" + encodeJsonString(candidate) + "?label=" + encode(type)),
                null);
        if (response.status() == 404) return null;
        if (response.status() != 200) {
            throw storageFailure(response.status(), "inspect vertex");
        }
        return response.json().path("id").asText(candidate);
    }

    private String findElementId(
            String collection, String property, String value, String label) {
        ObjectNode condition = objectMapper.createObjectNode().put(property, value);
        String path = "graph/" + collection + "?label=" + encode(label)
                + "&properties=" + encode(condition.toString()) + "&limit=1";
        JsonNode first = http.requireSuccess("GET", uri(path), null)
                .path(collection).path(0);
        return first.hasNonNull("id") ? first.path("id").asText() : null;
    }

    private String labelId(String label) {
        return labelIds.computeIfAbsent(label, value -> {
            JsonNode response = http.requireSuccess(
                    "GET", uri("schema/vertexlabels/" + value), null);
            String id = response.path("id").asText();
            if (id.isBlank()) {
                throw new ProjectionException(
                        "GRAPH_SCHEMA_INVALID", "HugeGraph omitted label id for " + value, false);
            }
            return id;
        });
    }

    private void property(String name, String type, String cardinality) {
        if (exists("schema/propertykeys/" + name)) return;
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        body.put("data_type", type);
        body.put("cardinality", cardinality);
        http.requireSuccess("POST", uri("schema/propertykeys"), body);
    }

    private void deleteSchema(String path) {
        JsonNode response = http.requireSuccess("DELETE", uri("schema/" + path), null);
        long taskId = response.path("task_id").asLong(-1);
        if (taskId < 0) return;
        for (int attempt = 0; attempt < 100; attempt++) {
            StorageHttpClient.Response taskResponse =
                    http.exchange("GET", uri("tasks/" + taskId), null);
            if (taskResponse.status() == 404) return;
            if (taskResponse.status() < 200 || taskResponse.status() >= 300) {
                throw storageFailure(taskResponse.status(), "inspect schema removal task");
            }
            String status = taskResponse.json().path("task_status").asText();
            if ("success".equals(status)) return;
            if ("failed".equals(status) || "cancelled".equals(status)) {
                throw new ProjectionException(
                        "GRAPH_SCHEMA_REBUILD_FAILED",
                        "HugeGraph failed to remove obsolete schema", false);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ProjectionException(
                        "GRAPH_SCHEMA_REBUILD_INTERRUPTED",
                        "HugeGraph schema removal was interrupted", true, interrupted);
            }
        }
        throw new ProjectionException(
                "GRAPH_SCHEMA_REBUILD_TIMEOUT",
                "HugeGraph schema removal did not finish in time", true);
    }

    private boolean exists(String path) {
        int status = http.exchange("GET", uri(path), null).status();
        if (status == 200) return true;
        if (status == 404) return false;
        throw new ProjectionException(
                "GRAPH_SCHEMA_UNAVAILABLE", "Cannot inspect HugeGraph schema", true);
    }

    private ObjectNode overrideStrategies(JsonNode properties) {
        ObjectNode strategies = objectMapper.createObjectNode();
        properties.fieldNames().forEachRemaining(field -> strategies.put(field, "OVERRIDE"));
        return strategies;
    }

    private ArrayNode strings(Iterable<String> values) {
        ArrayNode array = objectMapper.createArrayNode();
        values.forEach(array::add);
        return array;
    }

    private String objectKey(UUID ontologyId, String type, String id) {
        return stableKey(ontologyId + ":" + type, id);
    }

    private String relationKey(UUID ontologyId, String type, String id) {
        return stableKey(ontologyId + ":" + type, id);
    }

    private String stableKey(String namespace, String id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((namespace + "\u0000" + id).getBytes(StandardCharsets.UTF_8));
    }

    private Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException notIsoInstant) {
            try {
                return Instant.from(HUGEGRAPH_DATE.parse(value));
            } catch (RuntimeException invalid) {
                return Instant.now();
            }
        }
    }

    static String graphDate(Instant value) {
        return HUGEGRAPH_DATE.format(value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodeJsonString(String value) {
        try {
            return encode(objectMapper.writeValueAsString(value));
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot encode graph id", failure);
        }
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
            String ontologyId,
            String objectType,
            String objectId,
            long projectionSequence,
            JsonNode payload,
            String correlationId,
            String occurredAt) {
    }

    public record GraphRelation(
            String graphId,
            String ontologyId,
            String relationType,
            String relationId,
            String sourceObjectType,
            String sourceObjectId,
            String targetObjectType,
            String targetObjectId,
            long projectionSequence,
            JsonNode payload,
            String correlationId,
            String occurredAt) {
    }

    public record GraphSchemaRebuildResult(
            long objectCount, long relationCount, List<String> technicalFields) {
    }
}
