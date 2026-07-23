package com.hezhangjian.ontology.projection.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository.PropertyContract;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository.RelationContract;
import com.hezhangjian.ontology.projection.model.ProjectionException;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EventContractValidator {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private final ControlPlaneRepository repository;

    public EventContractValidator(ControlPlaneRepository repository) {
        this.repository = repository;
    }

    public ValidatedEvent validate(OntologyEventEnvelope event) {
        require(event.eventId() != null, "event_id is required");
        require(event.schemaVersion() == SUPPORTED_SCHEMA_VERSION, "Unsupported schema_version");
        require(event.ontologyId() != null, "ontology_id is required");
        require(event.ontologyRevision() > 0
                        && repository.revisionExists(event.ontologyId(), event.ontologyRevision()),
                "Unknown ontology_revision");
        require(event.occurredAt() != null, "occurred_at is required");
        require(StringUtils.hasText(event.producer()), "producer is required");
        require(StringUtils.hasText(event.correlationId()), "correlation_id is required");

        return switch (event.eventType()) {
            case "object.delete", "object.upsert" -> validateObject(event);
            case "relation.delete", "relation.upsert" -> validateRelation(event);
            default -> throw invalid("Unsupported event_type");
        };
    }

    private ValidatedEvent validateObject(OntologyEventEnvelope event) {
        require(StringUtils.hasText(event.objectType()), "object_type is required");
        require(StringUtils.hasText(event.objectId()), "object_id is required");
        require(event.objectVersion() != null && event.objectVersion() > 0, "object_version must be positive");
        Map<String, PropertyContract> contract = repository.objectProperties(
                event.ontologyRevision(), event.objectType());
        require(!contract.isEmpty(), "Unknown object_type at ontology_revision");
        boolean deleted = "object.delete".equals(event.eventType());
        JsonNode payload = event.payload();
        if (!deleted) {
            require(payload != null && payload.isObject(), "payload must be an object");
            validateProperties((ObjectNode) payload, contract);
        }
        return new ValidatedEvent(event, entityKey(event.ontologyId().toString(), "object", event.objectType(), event.objectId()),
                event.objectVersion(), filterSearchable(event.ontologyRevision(), event.objectType(), payload), deleted, false);
    }

    private ValidatedEvent validateRelation(OntologyEventEnvelope event) {
        require(StringUtils.hasText(event.relationType()), "relation_type is required");
        require(StringUtils.hasText(event.relationId()), "relation_id is required");
        require(event.relationVersion() != null && event.relationVersion() > 0,
                "relation_version must be positive");
        RelationContract relation = repository.relation(event.ontologyRevision(), event.relationType())
                .orElseThrow(() -> invalid("Unknown relation_type at ontology_revision"));
        require(relation.sourceTypeId().equals(event.sourceObjectType()), "Invalid relation source type");
        require(relation.targetTypeId().equals(event.targetObjectType()), "Invalid relation target type");
        require(StringUtils.hasText(event.sourceObjectId()), "source_object_id is required");
        require(StringUtils.hasText(event.targetObjectId()), "target_object_id is required");
        require(event.payload() == null || event.payload().isObject(), "payload must be an object");
        return new ValidatedEvent(event,
                entityKey(event.ontologyId().toString(), "relation", event.relationType(), event.relationId()),
                event.relationVersion(),
                event.payload(),
                "relation.delete".equals(event.eventType()),
                true);
    }

    private void validateProperties(ObjectNode payload, Map<String, PropertyContract> contract) {
        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            PropertyContract property = contract.get(field);
            require(property != null, "Unknown property: " + field);
            validateValue(field, payload.get(field), property.valueType());
        }
        contract.values().stream()
                .filter(PropertyContract::required)
                .forEach(property -> require(
                        payload.hasNonNull(property.propertyId()),
                        "Missing required property: " + property.propertyId()));
    }

    private void validateValue(String field, JsonNode value, String type) {
        if (value == null || value.isNull()) {
            return;
        }
        boolean valid = switch (type) {
            case "BOOLEAN" -> value.isBoolean();
            case "DECIMAL" -> value.isNumber();
            case "INTEGER", "LONG" -> value.isIntegralNumber();
            case "INTEGER_ARRAY" -> value.isArray()
                    && java.util.stream.StreamSupport.stream(value.spliterator(), false)
                    .allMatch(JsonNode::isIntegralNumber);
            case "JSON" -> value.isContainerNode();
            case "STRING_ARRAY" -> value.isArray()
                    && java.util.stream.StreamSupport.stream(value.spliterator(), false)
                    .allMatch(JsonNode::isTextual);
            case "DATE", "DATETIME", "ENUM", "STRING", "TEXT" -> value.isTextual();
            default -> false;
        };
        require(valid, "Invalid value type for property: " + field);
    }

    public JsonNode filterSearchable(long revision, String objectType, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        Map<String, PropertyContract> contract = repository.objectProperties(revision, objectType);
        ObjectNode searchable = ((ObjectNode) payload).objectNode();
        payload.properties().forEach(entry -> {
            PropertyContract property = contract.get(entry.getKey());
            if (property != null && property.searchable() && !property.sensitive()) {
                searchable.set(entry.getKey(), entry.getValue());
            }
        });
        return searchable;
    }

    private String entityKey(String ontologyId, String kind, String type, String id) {
        return ontologyId + ":" + kind + ":" + type + ":" + id;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private ProjectionException invalid(String message) {
        return new ProjectionException("CONTRACT_INVALID", message, false);
    }

    public record ValidatedEvent(
            OntologyEventEnvelope event,
            String entityKey,
            long entityVersion,
            JsonNode searchablePayload,
            boolean deleted,
            boolean relation) {
    }
}
