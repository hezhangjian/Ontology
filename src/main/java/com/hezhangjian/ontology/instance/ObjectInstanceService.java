package com.hezhangjian.ontology.instance;

import static com.hezhangjian.ontology.instance.ObjectInstanceModels.InstancePage;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.MutationResult;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.PropertySchema;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectInstanceService {
    private final ObjectInstanceRepository repository;
    private final ObjectMapper json;

    public ObjectInstanceService(ObjectInstanceRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Transactional
    public MutationResult create(
            UUID ontologyId,
            String ontologyApiName,
            String objectTypeApiName,
            Map<String, Object> properties,
            String idempotencyKey) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        ObjectNode payload = validate(schema, properties, true);
        String objectId = text(payload.get(schema.primaryKey().physicalKey()));
        String title = title(schema, payload);
        String requestHash = fingerprint(payload);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var replay = repository.idempotency(ontologyId, schema.objectTypeId(), idempotencyKey);
            if (replay.isPresent()) {
                if (!replay.get().requestHash().equals(requestHash)) {
                    throw new ObjectInstanceStoreException(
                            "IDEMPOTENCY_KEY_REUSED",
                            "Idempotency-Key was already used with a different request");
                }
                StoredInstance instance = repository.find(schema, replay.get().objectId(), false)
                        .orElseThrow(() -> new ObjectInstanceStoreException(
                                "OBJECT_INSTANCE_NOT_FOUND", "Object instance does not exist"));
                return new MutationResult(instance, UUID.randomUUID(), null);
            }
        }
        StoredInstance deleted = repository.find(schema, objectId, true)
                .filter(value -> value.deletedAt() != null)
                .orElse(null);
        StoredInstance instance = deleted == null
                ? repository.insert(
                        schema,
                        objectId,
                        title,
                        json.createObjectNode(),
                        payload,
                        "API",
                        null,
                        null)
                : repository.revive(
                        schema,
                        objectId,
                        deleted.version(),
                        title,
                        json.createObjectNode(),
                        payload,
                        "API",
                        null,
                        null);
        MutationResult result =
                enqueue(schema, ontologyApiName, instance, "create", "API", false);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            repository.saveIdempotency(
                    ontologyId,
                    schema.objectTypeId(),
                    idempotencyKey,
                    requestHash,
                    objectId,
                    instance.version());
        }
        return result;
    }

    public StoredInstance get(
            UUID ontologyId, String objectTypeApiName, String objectId) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        return repository.find(schema, objectId, false)
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "OBJECT_INSTANCE_NOT_FOUND", "Object instance does not exist"));
    }

    public InstancePage list(
            UUID ontologyId,
            String objectTypeApiName,
            Integer pageSize,
            String cursor) {
        return repository.list(
                repository.schema(ontologyId, objectTypeApiName),
                pageSize == null ? 50 : pageSize,
                decodeCursor(cursor));
    }

    @Transactional
    public MutationResult update(
            UUID ontologyId,
            String ontologyApiName,
            String objectTypeApiName,
            String objectId,
            long expectedVersion,
            Map<String, Object> patch) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        StoredInstance current = repository.find(schema, objectId, false)
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "OBJECT_INSTANCE_NOT_FOUND", "Object instance does not exist"));
        ObjectNode override = object(current.overridePayload()).deepCopy();
        for (Map.Entry<String, Object> edit : safeMap(patch).entrySet()) {
            PropertySchema property = schema.property(edit.getKey());
            if (property == null) {
                throw new ObjectInstanceStoreException(
                        "PROPERTY_UNKNOWN", "Unknown property: " + edit.getKey());
            }
            if (property.primaryKey()) {
                throw new ObjectInstanceStoreException(
                        "PRIMARY_KEY_IMMUTABLE", "The primary key property cannot be changed");
            }
            JsonNode value = json.valueToTree(edit.getValue());
            validateValue(property, value, false);
            if (value == null || value.isNull()) {
                override.remove(property.physicalKey());
            } else {
                override.set(property.physicalKey(), value);
            }
        }
        ObjectNode effective = merge(object(current.basePayload()), override);
        validateRequired(schema, effective);
        String title = title(schema, effective);
        StoredInstance updated = repository.update(
                schema,
                objectId,
                expectedVersion,
                title,
                current.basePayload(),
                override,
                current.sourceKind(),
                current.sourceRef(),
                current.sourceRevision());
        return enqueue(schema, ontologyApiName, updated, "update", "API", false);
    }

    @Transactional
    public MutationResult delete(
            UUID ontologyId,
            String ontologyApiName,
            String objectTypeApiName,
            String objectId,
            long expectedVersion) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        StoredInstance deleted = repository.tombstone(schema, objectId, expectedVersion);
        return enqueue(schema, ontologyApiName, deleted, "delete", "API", true);
    }

    @Transactional
    public MutationResult clearOverrides(
            UUID ontologyId,
            String ontologyApiName,
            String objectTypeApiName,
            String objectId) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        StoredInstance current = repository.find(schema, objectId, false)
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "OBJECT_INSTANCE_NOT_FOUND", "Object instance does not exist"));
        ObjectNode base = object(current.basePayload());
        validateRequired(schema, base);
        StoredInstance updated = repository.update(
                schema,
                objectId,
                current.version(),
                title(schema, base),
                current.basePayload(),
                json.createObjectNode(),
                current.sourceKind(),
                current.sourceRef(),
                current.sourceRevision());
        return enqueue(
                schema, ontologyApiName, updated, "update", "ACTION", false);
    }

    @Transactional
    public MutationResult mergeBase(
            UUID ontologyId,
            String ontologyApiName,
            String objectTypeApiName,
            Map<String, Object> properties,
            String sourceKind,
            String sourceRef,
            String sourceRevision,
            UUID correlationId) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        ObjectNode base = validate(schema, properties, true);
        String objectId = text(base.get(schema.primaryKey().physicalKey()));
        StoredInstance current = repository.find(schema, objectId, true).orElse(null);
        if (current == null) {
            StoredInstance inserted = repository.insert(
                    schema,
                    objectId,
                    title(schema, base),
                    base,
                    json.createObjectNode(),
                    sourceKind,
                    sourceRef,
                    sourceRevision);
            return enqueue(
                    schema, ontologyApiName, inserted, "create", sourceKind, false, correlationId);
        }
        if (current.deletedAt() != null) {
            StoredInstance revived = repository.revive(
                    schema,
                    objectId,
                    current.version(),
                    title(schema, base),
                    base,
                    current.overridePayload(),
                    sourceKind,
                    sourceRef,
                    sourceRevision);
            return enqueue(
                    schema, ontologyApiName, revived, "create", sourceKind, false, correlationId);
        }
        boolean sameSource = java.util.Objects.equals(current.sourceKind(), sourceKind)
                && java.util.Objects.equals(current.sourceRef(), sourceRef);
        if (object(current.basePayload()).equals(base) && sameSource) {
            return new MutationResult(current, correlationId, null);
        }
        ObjectNode effective = merge(base, object(current.overridePayload()));
        StoredInstance updated = repository.update(
                schema,
                objectId,
                current.version(),
                title(schema, effective),
                base,
                current.overridePayload(),
                sourceKind,
                sourceRef,
                sourceRevision);
        return enqueue(
                schema, ontologyApiName, updated, "update", sourceKind, false, correlationId);
    }

    @Transactional
    public MutationResult deleteFromSource(
            UUID ontologyId,
            String ontologyApiName,
            String objectTypeApiName,
            String objectId,
            long expectedVersion,
            String sourceKind,
            UUID correlationId) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        StoredInstance deleted = repository.tombstone(schema, objectId, expectedVersion);
        return enqueue(
                schema,
                ontologyApiName,
                deleted,
                "delete",
                sourceKind,
                true,
                correlationId);
    }

    public Map<String, Object> externalProperties(
            ObjectSchema schema, StoredInstance instance) {
        ObjectNode effective = merge(object(instance.basePayload()), object(instance.overridePayload()));
        Map<String, Object> values = new LinkedHashMap<>();
        for (PropertySchema property : schema.properties()) {
            if (!property.sensitive() && effective.has(property.physicalKey())) {
                values.put(
                        property.displayName(),
                        json.convertValue(effective.get(property.physicalKey()), Object.class));
            }
        }
        return values;
    }

    public String validateForImport(
            UUID ontologyId, String objectTypeApiName, Map<String, Object> properties) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        ObjectNode payload = validate(schema, properties, true);
        return text(payload.get(schema.primaryKey().physicalKey()));
    }

    @Transactional
    public int reconcileSchema(
            UUID ontologyId, String ontologyApiName, String objectTypeApiName) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        int changed = 0;
        String cursor = null;
        do {
            InstancePage page = repository.list(schema, 200, cursor);
            for (StoredInstance current : page.items()) {
                ObjectNode effective =
                        merge(object(current.basePayload()), object(current.overridePayload()));
                validateRequired(schema, effective);
                String currentTitle = title(schema, effective);
                if (!currentTitle.equals(current.title())) {
                    StoredInstance updated = repository.update(
                            schema,
                            current.id(),
                            current.version(),
                            currentTitle,
                            current.basePayload(),
                            current.overridePayload(),
                            current.sourceKind(),
                            current.sourceRef(),
                            current.sourceRevision());
                    enqueue(schema, ontologyApiName, updated, "update", "SCHEMA", false);
                    changed++;
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return changed;
    }

    @Transactional
    public int tombstoneAll(
            UUID ontologyId, String ontologyApiName, String objectTypeApiName) {
        ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
        int changed = 0;
        String cursor = null;
        do {
            InstancePage page = repository.list(schema, 200, cursor);
            for (StoredInstance current : page.items()) {
                StoredInstance deleted =
                        repository.tombstone(schema, current.id(), current.version());
                enqueue(schema, ontologyApiName, deleted, "delete", "SCHEMA", true);
                changed++;
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return changed;
    }

    public String encodeCursor(String objectId) {
        return objectId == null
                ? null
                : java.util.Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(objectId.getBytes(StandardCharsets.UTF_8));
    }

    public String decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return new String(
                    java.util.Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new ObjectInstanceStoreException("CURSOR_INVALID", "Cursor is invalid", failure);
        }
    }

    private MutationResult enqueue(
            ObjectSchema schema,
            String ontologyApiName,
            StoredInstance instance,
            String eventType,
            String source,
            boolean deleted) {
        return enqueue(
                schema,
                ontologyApiName,
                instance,
                eventType,
                source,
                deleted,
                UUID.randomUUID());
    }

    private MutationResult enqueue(
            ObjectSchema schema,
            String ontologyApiName,
            StoredInstance instance,
            String eventType,
            String source,
            boolean deleted,
            UUID correlationId) {
        UUID eventId = UUID.randomUUID();
        ObjectNode effective =
                merge(object(instance.basePayload()), object(instance.overridePayload()));
        ObjectInstanceEvent event = new ObjectInstanceEvent(
                eventId,
                eventType,
                1,
                schema.ontologyId(),
                ontologyApiName,
                schema.objectTypeId(),
                schema.objectTypeApiName(),
                schema.objectTypePhysicalKey(),
                instance.id(),
                instance.version(),
                instance.title(),
                effective,
                Instant.now(),
                correlationId,
                source,
                deleted);
        try {
            repository.enqueue(schema, event, ontologyApiName, json.writeValueAsString(event));
        } catch (Exception failure) {
            throw new IllegalStateException("Object instance event could not be queued", failure);
        }
        return new MutationResult(instance, correlationId, eventId);
    }

    private ObjectNode validate(
            ObjectSchema schema, Map<String, Object> input, boolean requireAll) {
        Map<String, Object> values = safeMap(input);
        ObjectNode payload = json.createObjectNode();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            PropertySchema property = schema.property(entry.getKey());
            if (property == null) {
                throw new ObjectInstanceStoreException(
                        "PROPERTY_UNKNOWN", "Unknown property: " + entry.getKey());
            }
            JsonNode value = json.valueToTree(entry.getValue());
            validateValue(property, value, requireAll);
            if (value != null && !value.isNull()) {
                payload.set(property.physicalKey(), value);
            }
        }
        if (requireAll) {
            validateRequired(schema, payload);
        }
        return payload;
    }

    private void validateRequired(ObjectSchema schema, ObjectNode payload) {
        for (PropertySchema property : schema.properties()) {
            if (property.required()
                    && (!payload.has(property.physicalKey())
                            || payload.get(property.physicalKey()).isNull())) {
                throw new ObjectInstanceStoreException(
                        "PROPERTY_REQUIRED", "Required property is missing: " + property.displayName());
            }
        }
    }

    private void validateValue(PropertySchema property, JsonNode value, boolean creating) {
        if (value == null || value.isNull()) {
            if (creating && property.required()) {
                throw new ObjectInstanceStoreException(
                        "PROPERTY_REQUIRED", "Required property is missing: " + property.displayName());
            }
            return;
        }
        boolean valid = switch (property.valueType()) {
            case "BOOLEAN" -> value.isBoolean();
            case "DECIMAL" -> value.isNumber() && decimal(value) != null;
            case "INTEGER" -> value.isIntegralNumber()
                    && value.canConvertToInt();
            case "LONG" -> value.isIntegralNumber() && value.canConvertToLong();
            case "INTEGER_ARRAY" -> value.isArray()
                    && all(value.elements(), JsonNode::isIntegralNumber);
            case "JSON" -> value.isArray() || value.isObject();
            case "STRING_ARRAY" -> value.isArray() && all(value.elements(), JsonNode::isTextual);
            case "DATE" -> value.isTextual() && parsesDate(value.asText());
            case "DATETIME" -> value.isTextual() && parsesDateTime(value.asText());
            default -> value.isTextual();
        };
        if (!valid) {
            throw new ObjectInstanceStoreException(
                    "PROPERTY_TYPE_INVALID",
                    "Property " + property.displayName() + " must be " + property.valueType());
        }
        if (property.primaryKey() && text(value).isBlank()) {
            throw new ObjectInstanceStoreException(
                    "PRIMARY_KEY_INVALID", "Primary key cannot be blank");
        }
    }

    private String title(ObjectSchema schema, ObjectNode payload) {
        JsonNode value = payload.get(schema.titleProperty().physicalKey());
        String title = text(value);
        if (title.isBlank()) {
            throw new ObjectInstanceStoreException(
                    "TITLE_PROPERTY_INVALID", "Title property cannot be blank");
        }
        return title;
    }

    private ObjectNode object(JsonNode value) {
        return value != null && value.isObject()
                ? (ObjectNode) value
                : json.createObjectNode();
    }

    private ObjectNode merge(ObjectNode base, ObjectNode overrides) {
        ObjectNode result = base.deepCopy();
        overrides.fields().forEachRemaining(entry -> result.set(entry.getKey(), entry.getValue()));
        return result;
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }

    private String text(JsonNode value) {
        return value == null || value.isNull() ? "" : value.asText();
    }

    private BigDecimal decimal(JsonNode value) {
        try {
            return value.decimalValue();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private boolean parsesDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean parsesDateTime(String value) {
        try {
            OffsetDateTime.parse(value);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private boolean all(Iterator<JsonNode> values, java.util.function.Predicate<JsonNode> test) {
        while (values.hasNext()) {
            if (!test.test(values.next())) {
                return false;
            }
        }
        return true;
    }

    private String fingerprint(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
