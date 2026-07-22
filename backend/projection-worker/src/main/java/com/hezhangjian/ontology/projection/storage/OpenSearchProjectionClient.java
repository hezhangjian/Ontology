package com.hezhangjian.ontology.projection.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.config.ProjectionProperties;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient.GraphObject;
import com.hezhangjian.ontology.projection.validation.EventContractValidator.ValidatedEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OpenSearchProjectionClient {
    private static final String OBJECT_ALIAS = "platform-ontology-objects";
    private static final String RELATION_ALIAS = "platform-ontology-relations";
    private static final DateTimeFormatter INDEX_TIME = DateTimeFormatter
            .ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final StorageHttpClient http;

    public OpenSearchProjectionClient(
            ProjectionProperties properties,
            ObjectMapper objectMapper,
            StorageHttpClient http) {
        this.baseUri = ensureTrailingSlash(properties.opensearchUrl());
        this.objectMapper = objectMapper;
        this.http = http;
    }

    public void ensureIndexes() {
        putTemplate("platform-ontology-objects-template", "platform-ontology-objects-*");
        putTemplate("platform-ontology-relations-template", "platform-ontology-relations-*");
        ensureAlias(OBJECT_ALIAS, "platform-ontology-objects-v1");
        ensureAlias(RELATION_ALIAS, "platform-ontology-relations-v1");
    }

    public void apply(ValidatedEvent validated, String graphElementId) {
        String alias = validated.relation() ? RELATION_ALIAS : OBJECT_ALIAS;
        String id = stableId(validated.entityKey());
        if (validated.deleted()) {
            StorageHttpClient.Response response = http.exchange(
                    "DELETE", uri(alias + "/_doc/" + id + "?refresh=wait_for"), null);
            if (response.status() != 200 && response.status() != 404) {
                throw storageFailure(response.status(), "delete index document");
            }
            return;
        }
        http.requireSuccess(
                "PUT",
                uri(alias + "/_doc/" + id + "?refresh=wait_for&version="
                        + validated.entityVersion() + "&version_type=external_gte"),
                document(validated, graphElementId));
    }

    public void applyBatch(List<ValidatedEvent> events, List<String> graphElementIds) {
        if (events.size() != graphElementIds.size()) {
            throw new IllegalArgumentException("Search batch events and graph ids must have equal sizes");
        }
        if (events.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (int index = 0; index < events.size(); index++) {
            ValidatedEvent validated = events.get(index);
            String alias = validated.relation() ? RELATION_ALIAS : OBJECT_ALIAS;
            ObjectNode metadata = objectMapper.createObjectNode()
                    .put("_index", alias)
                    .put("_id", stableId(validated.entityKey()))
                    .put("version", validated.entityVersion())
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
        for (JsonNode item : items) {
            JsonNode result = item.elements().hasNext() ? item.elements().next() : null;
            int status = result == null ? 500 : result.path("status").asInt(500);
            boolean deleteMissing = item.has("delete") && status == 404;
            if ((status < 200 || status >= 300) && !deleteMissing) {
                throw storageFailure(status, "apply bulk index document");
            }
        }
    }

    public RebuildResult rebuildObjects(List<GraphObject> objects, SearchablePayloadFilter filter) {
        String newIndex = "platform-ontology-objects-rebuild-" + INDEX_TIME.format(Instant.now());
        createIndex(newIndex, null);
        long count = 0;
        for (GraphObject object : objects) {
            ObjectNode document = objectMapper.createObjectNode();
            document.put("graph_element_id", object.graphId());
            document.put("object_type", object.objectType());
            document.put("object_id", object.objectId());
            document.put("ontology_revision", object.ontologyRevision());
            document.put("entity_version", object.objectVersion());
            document.put("correlation_id", object.correlationId());
            document.put("occurred_at", object.occurredAt());
            document.set("visibility_tokens", objectMapper.createArrayNode().add("authenticated"));
            document.set("properties", filter.filter(
                    object.ontologyRevision(), object.objectType(), object.payload()));
            http.requireSuccess(
                    "PUT",
                    uri(newIndex + "/_doc/" + stableId("object:" + object.objectType() + ":" + object.objectId())
                            + "?refresh=false&version=" + object.objectVersion() + "&version_type=external_gte"),
                    document);
            count++;
        }
        http.requireSuccess("POST", uri(newIndex + "/_refresh"), null);
        switchAlias(OBJECT_ALIAS, newIndex);
        return new RebuildResult(newIndex, count);
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

    private ObjectNode indexDefinition(String alias) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("settings", objectMapper.createObjectNode()
                .put("number_of_shards", 1)
                .put("number_of_replicas", 0));
        ObjectNode fields = objectMapper.createObjectNode();
        keyword(fields, "graph_element_id");
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
        fields.set("ontology_revision", objectMapper.createObjectNode().put("type", "long"));
        fields.set("entity_version", objectMapper.createObjectNode().put("type", "long"));
        fields.set("occurred_at", objectMapper.createObjectNode().put("type", "date"));
        fields.set("properties", objectMapper.createObjectNode().put("type", "object").put("dynamic", true));
        root.set("mappings", objectMapper.createObjectNode()
                .put("dynamic", "strict")
                .put("date_detection", false)
                .set("properties", fields));
        if (alias != null) {
            root.set("aliases", objectMapper.createObjectNode().set(alias, objectMapper.createObjectNode()));
        }
        return root;
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
        document.put("ontology_revision", event.ontologyRevision());
        document.put("entity_version", validated.entityVersion());
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

    @FunctionalInterface
    public interface SearchablePayloadFilter {
        JsonNode filter(long revision, String objectType, JsonNode payload);
    }

    public record RebuildResult(String index, long objectCount) {
    }
}
