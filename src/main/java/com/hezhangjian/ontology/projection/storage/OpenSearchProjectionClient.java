package com.hezhangjian.ontology.projection.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.config.OpenSearchProperties;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient.GraphObject;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient.GraphRelation;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OpenSearchProjectionClient {
    private static final String RELATION_ALIAS = "platform-ontology-relations";
    private static final DateTimeFormatter INDEX_TIME = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final StorageHttpClient http;

    public OpenSearchProjectionClient(
            OpenSearchProperties properties,
            ObjectMapper objectMapper,
            StorageHttpClient http) {
        this.baseUri = ensureTrailingSlash(properties.url());
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public void ensureIndexes() {
        putTemplate("platform-ontology-relations-template", "platform-ontology-relations-*");
        ensureAlias(RELATION_ALIAS, "platform-ontology-relations-v1");
        ensureMapping(RELATION_ALIAS);
    }

    public ApplyResult apply(ValidatedEvent validated, String graphElementId) {
        String alias = index(validated);
        String id = stableId(validated.entityKey());
        if (validated.deleted()) {
            StorageHttpClient.Response response = http.exchange(
                    "DELETE", uri(alias + "/_doc/" + id + "?refresh=wait_for"), null);
            if (response.status() == 409) {
                return ApplyResult.STALE;
            }
            if (response.status() != 200 && response.status() != 404) {
                throw storageFailure(response.status(), "delete index document");
            }
            return ApplyResult.APPLIED;
        }
        StorageHttpClient.Response response = http.exchange(
                "PUT",
                uri(alias + "/_doc/" + id + "?refresh=wait_for&version="
                        + validated.projectionSequence() + "&version_type=external_gte"),
                document(validated, graphElementId));
        if (response.status() == 409) {
            return ApplyResult.STALE;
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw storageFailure(response.status(), "index document");
        }
        return ApplyResult.APPLIED;
    }

    public ApplyResult repair(ValidatedEvent validated, String graphElementId) {
        String index = index(validated);
        String id = stableId(validated.entityKey());
        StorageHttpClient.Response deleted =
                http.exchange("DELETE", uri(index + "/_doc/" + id + "?refresh=wait_for"), null);
        if (deleted.status() != 200 && deleted.status() != 404) {
            throw storageFailure(deleted.status(), "reset index document for repair");
        }
        return apply(validated, graphElementId);
    }

    public List<ApplyResult> applyBatch(List<ValidatedEvent> events, List<String> graphElementIds) {
        if (events.size() != graphElementIds.size()) {
            throw new IllegalArgumentException("Search batch events and graph ids must have equal sizes");
        }
        if (events.isEmpty()) {
            return List.of();
        }
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < events.size(); index++) {
            ValidatedEvent validated = events.get(index);
            String alias = index(validated);
            ObjectNode metadata = objectMapper.createObjectNode()
                    .put("_index", alias)
                    .put("_id", stableId(validated.entityKey()))
                    .put("version", validated.projectionSequence())
                    .put("version_type", "external_gte");
            if (validated.deleted()) {
                appendBulkLine(body, objectMapper.createObjectNode().set("delete", metadata));
            } else {
                appendBulkLine(body, objectMapper.createObjectNode().set("index", metadata));
                appendBulkLine(body, document(validated, graphElementIds.get(index)));
            }
        }
        JsonNode response = http.requireSuccessRaw(
                "POST",
                uri("_bulk?refresh=wait_for"),
                body.toString(),
                "application/x-ndjson");
        JsonNode items = response.path("items");
        if (!items.isArray() || items.size() != events.size()) {
            throw new ProjectionException(
                    "SEARCH_RESPONSE_INVALID", "OpenSearch omitted bulk item results", true);
        }
        List<ApplyResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode result = item.elements().hasNext() ? item.elements().next() : null;
            int status = result == null ? 500 : result.path("status").asInt(500);
            boolean deleteMissing = item.has("delete") && status == 404;
            if (status == 409) {
                results.add(ApplyResult.STALE);
                continue;
            }
            if ((status < 200 || status >= 300) && !deleteMissing) {
                throw new ProjectionException(
                        "SEARCH_BULK_ITEM_" + status,
                        "OpenSearch rejected a bulk item: " + result.path("error"),
                        status == 408 || status == 429 || status >= 500);
            }
            results.add(ApplyResult.APPLIED);
        }
        return List.copyOf(results);
    }

    public void deleteObjectType(UUID ontologyId, String objectType) {
        String objectIndex = "ontology-object-"
                + objectType.toLowerCase(java.util.Locale.ROOT);
        StorageHttpClient.Response deleted =
                http.exchange("DELETE", uri(objectIndex), null);
        if (deleted.status() != 200 && deleted.status() != 404) {
            throw storageFailure(deleted.status(), "delete object type index");
        }

        ArrayNode endpoints = objectMapper.createArrayNode()
                .add(term("source_object_type", objectType))
                .add(term("target_object_type", objectType));
        ObjectNode endpointCondition = objectMapper.createObjectNode();
        endpointCondition.set("should", endpoints);
        endpointCondition.put("minimum_should_match", 1);
        ObjectNode relationFilter = boolFilter(
                term("ontology_id", ontologyId.toString()),
                objectMapper.createObjectNode().set(
                        "bool",
                        endpointCondition));
        http.requireSuccess(
                "POST",
                uri(RELATION_ALIAS + "/_delete_by_query?refresh=true"),
                objectMapper.createObjectNode().set("query", relationFilter));
    }

    public void deleteRelationType(UUID ontologyId, String relationType) {
        ObjectNode filter = boolFilter(
                term("ontology_id", ontologyId.toString()),
                term("relation_type", relationType));
        http.requireSuccess(
                "POST",
                uri(RELATION_ALIAS + "/_delete_by_query?refresh=true"),
                objectMapper.createObjectNode().set("query", filter));
    }

    public RebuildResult rebuildRelations(List<GraphRelation> relations) {
        String newIndex = "platform-ontology-relations-rebuild-" + INDEX_TIME.format(Instant.now());
        createIndex(newIndex, null);
        long count = 0;
        for (GraphRelation relation : relations) {
            ObjectNode document = objectMapper.createObjectNode();
            document.put("graph_element_id", relation.graphId());
            document.put("ontology_id", relation.ontologyId());
            document.put("relation_type", relation.relationType());
            document.put("relation_id", relation.relationId());
            document.put("source_object_type", relation.sourceObjectType());
            document.put("source_object_id", relation.sourceObjectId());
            document.put("target_object_type", relation.targetObjectType());
            document.put("target_object_id", relation.targetObjectId());
            document.put("projection_sequence", relation.projectionSequence());
            document.put("correlation_id", relation.correlationId());
            document.put("occurred_at", relation.occurredAt());
            document.set("visibility_tokens", objectMapper.createArrayNode().add("authenticated"));
            document.set("properties", relation.payload());
            http.requireSuccess(
                    "PUT",
                    uri(newIndex + "/_doc/" + stableId(relation.ontologyId() + ":relation:"
                            + relation.relationType() + ":" + relation.relationId())
                            + "?refresh=false&version=" + relation.projectionSequence()
                            + "&version_type=external_gte"),
                    document);
            count++;
        }
        http.requireSuccess("POST", uri(newIndex + "/_refresh"), null);
        switchAlias(RELATION_ALIAS, newIndex);
        return new RebuildResult(newIndex, count);
    }

    public List<GraphObject> currentObjects(UUID ontologyId, String objectType) {
        String index = "ontology-object-" + objectType.toLowerCase(java.util.Locale.ROOT);
        StorageHttpClient.Response exists = http.exchange("HEAD", uri(index), null);
        if (exists.status() == 404) {
            return List.of();
        }
        if (exists.status() < 200 || exists.status() >= 300) {
            throw storageFailure(exists.status(), "inspect object type index");
        }
        return graphObjects(currentDocuments(index)).stream()
                .filter(object -> ontologyId.toString().equals(object.ontologyId()))
                .filter(object -> objectType.equals(object.objectType()))
                .toList();
    }

    private List<GraphObject> graphObjects(JsonNode hits) {
        List<GraphObject> objects = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode value = hit.path("_source");
            objects.add(new GraphObject(
                    value.path("graph_element_id").asText(),
                    value.path("ontology_id").asText(),
                    value.path("object_type").asText(),
                    value.path("object_id").asText(),
                    value.path("projection_sequence").asLong(),
                    value.path("properties"),
                    value.path("correlation_id").asText(),
                    value.path("occurred_at").asText()));
        }
        return List.copyOf(objects);
    }

    public List<GraphRelation> currentRelations() {
        JsonNode hits = currentDocuments(RELATION_ALIAS);
        List<GraphRelation> relations = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode value = hit.path("_source");
            relations.add(new GraphRelation(
                    value.path("graph_element_id").asText(),
                    value.path("ontology_id").asText(),
                    value.path("relation_type").asText(),
                    value.path("relation_id").asText(),
                    value.path("source_object_type").asText(),
                    value.path("source_object_id").asText(),
                    value.path("target_object_type").asText(),
                    value.path("target_object_id").asText(),
                    value.path("projection_sequence").asLong(),
                    value.path("properties"),
                    value.path("correlation_id").asText(),
                    value.path("occurred_at").asText()));
        }
        return List.copyOf(relations);
    }

    private JsonNode currentDocuments(String alias) {
        ObjectNode query = objectMapper.createObjectNode();
        query.put("size", 10_000);
        query.set("query", objectMapper.createObjectNode().set(
                "match_all", objectMapper.createObjectNode()));
        JsonNode response = http.requireSuccess(
                "POST", uri(alias + "/_search"), query);
        return response.path("hits").path("hits");
    }

    private void putTemplate(String name, String pattern) {
        ObjectNode body = objectMapper.createObjectNode();
        body.set("index_patterns", objectMapper.createArrayNode().add(pattern));
        body.set("template", indexDefinition(null));
        http.requireSuccess("PUT", uri("_index_template/" + name), body);
    }

    private void ensureAlias(String alias, String initialIndex) {
        StorageHttpClient.Response response = http.exchange("HEAD", uri("_alias/" + alias), null);
        if (response.status() == 200) {
            return;
        }
        if (response.status() != 404) {
            throw storageFailure(response.status(), "inspect alias");
        }
        createIndex(initialIndex, alias);
    }

    private void createIndex(String index, String alias) {
        StorageHttpClient.Response existing = http.exchange("HEAD", uri(index), null);
        if (existing.status() == 200) {
            if (alias != null) {
                http.requireSuccess("PUT", uri(index + "/_alias/" + alias), objectMapper.createObjectNode());
            }
            return;
        }
        if (existing.status() != 404) {
            throw storageFailure(existing.status(), "inspect index");
        }
        http.requireSuccess("PUT", uri(index), indexDefinition(alias));
    }

    private void ensureMapping(String alias) {
        http.requireSuccess("PUT", uri(alias + "/_mapping"), mappingDefinition());
    }

    private ObjectNode indexDefinition(String alias) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("settings", objectMapper.createObjectNode()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0));
        root.set("mappings", mappingDefinition());
        if (alias != null) {
            root.set("aliases", objectMapper.createObjectNode().set(alias, objectMapper.createObjectNode()));
        }
        return root;
    }

    private ObjectNode mappingDefinition() {
        ObjectNode fields = objectMapper.createObjectNode();
        keyword(fields, "graph_element_id");
        keyword(fields, "ontology_id");
        keyword(fields, "object_type");
        keyword(fields, "object_id");
        keyword(fields, "relation_type");
        keyword(fields, "relation_id");
        keyword(fields, "source_object_type");
        keyword(fields, "source_object_id");
        keyword(fields, "target_object_type");
        keyword(fields, "target_object_id");
        keyword(fields, "correlation_id");
        keyword(fields, "visibility_tokens");
        fields.set("projection_sequence", objectMapper.createObjectNode().put("type", "long"));
        fields.set("occurred_at", objectMapper.createObjectNode().put("type", "date"));
        fields.set("properties", objectMapper.createObjectNode().put("type", "object").put("dynamic", true));
        return objectMapper.createObjectNode()
                .put("dynamic", "strict")
                .put("date_detection", false)
                .set("properties", fields);
    }

    private ObjectNode boolFilter(ObjectNode... filters) {
        ArrayNode values = objectMapper.createArrayNode();
        for (ObjectNode filter : filters) values.add(filter);
        return objectMapper.createObjectNode().set(
                "bool",
                objectMapper.createObjectNode().set("filter", values));
    }

    private ObjectNode term(String field, String value) {
        return objectMapper.createObjectNode().set(
                "term",
                objectMapper.createObjectNode().put(field, value));
    }

    private void switchAlias(String alias, String newIndex) {
        StorageHttpClient.Response current = http.exchange("GET", uri("_alias/" + alias), null);
        List<String> oldIndexes = new ArrayList<>();
        if (current.status() == 200) {
            current.json().fieldNames().forEachRemaining(oldIndexes::add);
        } else if (current.status() != 404) {
            throw storageFailure(current.status(), "inspect alias before rebuild");
        }
        ArrayNode actions = objectMapper.createArrayNode();
        for (String oldIndex : oldIndexes) {
            ObjectNode remove = objectMapper.createObjectNode();
            remove.set("remove", objectMapper.createObjectNode().put("index", oldIndex).put("alias", alias));
            actions.add(remove);
        }
        ObjectNode add = objectMapper.createObjectNode();
        add.set("add", objectMapper.createObjectNode().put("index", newIndex).put("alias", alias));
        actions.add(add);
        http.requireSuccess("POST", uri("_aliases"), objectMapper.createObjectNode().set("actions", actions));
    }

    private void keyword(ObjectNode fields, String field) {
        fields.set(field, objectMapper.createObjectNode().put("type", "keyword"));
    }

    private ObjectNode document(ValidatedEvent validated, String graphElementId) {
        OntologyEventEnvelope event = validated.event();
        ObjectNode document = objectMapper.createObjectNode();
        document.put("graph_element_id", graphElementId);
        document.put("ontology_id", event.ontologyId().toString());
        document.put("projection_sequence", validated.projectionSequence());
        document.put("correlation_id", event.correlationId());
        document.put("occurred_at", event.occurredAt().toString());
        document.set("visibility_tokens", objectMapper.createArrayNode().add("authenticated"));
        if (validated.relation()) {
            document.put("relation_type", event.relationType());
            document.put("relation_id", event.relationId());
            document.put("source_object_type", event.sourceObjectType());
            document.put("source_object_id", event.sourceObjectId());
            document.put("target_object_type", event.targetObjectType());
            document.put("target_object_id", event.targetObjectId());
        } else {
            document.put("object_type", event.objectType());
            document.put("object_id", event.objectId());
        }
        document.set("properties", validated.searchablePayload());
        return document;
    }

    private String index(ValidatedEvent validated) {
        if (validated.relation()) {
            return RELATION_ALIAS;
        }
        String value = "ontology-object-" + validated.event().objectType().toLowerCase(
                java.util.Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,254}")) {
            throw new ProjectionException(
                    "SEARCH_INDEX_INVALID", "Object type cannot be mapped to an index", false);
        }
        StorageHttpClient.Response response = http.exchange("HEAD", uri(value), null);
        if (response.status() == 404) {
            createIndex(value, null);
        } else if (response.status() < 200 || response.status() >= 300) {
            throw storageFailure(response.status(), "inspect object type index");
        }
        return value;
    }

    private void appendBulkLine(StringBuilder body, JsonNode line) {
        try {
            body.append(objectMapper.writeValueAsString(line)).append('\n');
        } catch (Exception exception) {
            throw new ProjectionException(
                    "SEARCH_REQUEST_INVALID", "OpenSearch bulk request could not be encoded", false, exception);
        }
    }

    private String stableId(String key) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private URI ensureTrailingSlash(URI value) {
        String text = value.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private URI uri(String path) {
        return baseUri.resolve(path);
    }

    private ProjectionException storageFailure(int status, String operation) {
        return new ProjectionException(
                "SEARCH_HTTP_" + status,
                "OpenSearch failed to " + operation + " with HTTP " + status,
                status == 408 || status == 429 || status >= 500);
    }

    public record RebuildResult(String index, long objectCount) {
    }

    public enum ApplyResult {
        APPLIED,
        STALE
    }
}
