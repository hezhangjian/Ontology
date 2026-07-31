package com.hezhangjian.ontology.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ObjectInstanceAuthorityReader {
    private final ObjectInstanceRepository instances;
    private final ObjectMapper json;
    private final SqlClientRepository jdbc;

    public ObjectInstanceAuthorityReader(
            ObjectInstanceRepository instances, ObjectMapper json, SqlClientRepository jdbc) {
        this.instances = instances;
        this.json = json;
        this.jdbc = jdbc;
    }

    public Optional<StoredInstance> find(
            UUID ontologyId, UUID objectTypeId, String objectId) {
        ObjectSchema schema = schema(ontologyId, objectTypeId);
        return instances.find(schema, objectId, false);
    }

    public boolean exists(
            UUID ontologyId, String objectTypePhysicalKey, String objectId) {
        ObjectSchema schema = schema(ontologyId, objectTypePhysicalKey);
        return instances.find(schema, objectId, false).isPresent();
    }

    public List<AuthoritativeObject> list(
            UUID ontologyId, String objectTypePhysicalKey) {
        ObjectSchema schema = schema(ontologyId, objectTypePhysicalKey);
        List<AuthoritativeObject> result = new ArrayList<>();
        String cursor = null;
        do {
            var page = instances.list(schema, 200, cursor);
            page.items().forEach(value -> result.add(new AuthoritativeObject(
                    value.id(), effective(value), value.version())));
            cursor = page.nextCursor();
        } while (cursor != null);
        return List.copyOf(result);
    }

    public long count(UUID ontologyId, UUID objectTypeId) {
        return instances.count(schema(ontologyId, objectTypeId));
    }

    public long count(UUID ontologyId, String objectTypePhysicalKey) {
        return instances.count(schema(ontologyId, objectTypePhysicalKey));
    }

    public ObjectSchema schema(UUID ontologyId, UUID objectTypeId) {
        return instances.schema(ontologyId, apiName(ontologyId, "id", objectTypeId));
    }

    public ObjectSchema schema(UUID ontologyId, String objectTypePhysicalKey) {
        return instances.schema(
                ontologyId, apiName(ontologyId, "physical_key", objectTypePhysicalKey));
    }

    public ObjectNode effective(StoredInstance value) {
        ObjectNode result = value.basePayload().isObject()
                ? ((ObjectNode) value.basePayload()).deepCopy()
                : json.createObjectNode();
        if (value.overridePayload().isObject()) {
            value.overridePayload().fields()
                    .forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private String apiName(UUID ontologyId, String column, Object value) {
        if (!List.of("id", "physical_key").contains(column)) {
            throw new IllegalArgumentException("Unsupported object type lookup");
        }
        return jdbc.sql("""
                SELECT api_name
                FROM control.ontology_resources
                WHERE ontology_id=:ontology AND kind='OBJECT_TYPE' AND %s=:value
                """.formatted(column))
                .param("ontology", ontologyId)
                .param("value", value)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "OBJECT_TYPE_NOT_FOUND", "Object type does not exist"));
    }

    public record AuthoritativeObject(String id, JsonNode payload, long version) {}
}
