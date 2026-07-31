package com.hezhangjian.ontology.projection.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.contracts.projection.OntologyEventEnvelope;
import com.hezhangjian.ontology.repo.ControlPlaneRepository;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.PropertyContract;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.RelationContract;
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
        require(event.occurredAt() != null, "occurred_at is required");
        require(StringUtils.hasText(event.producer()), "producer is required");
        require(StringUtils.hasText(event.correlationId()), "correlation_id is required");

        return switch (event.eventType()) {
            case "object.clear_overrides", "object.delete", "object.upsert" -> validateObject(event);
            case "relation.delete", "relation.upsert" -> validateRelation(event);
            default -> throw invalid("Unsupported event_type");
        };
    }

    private ValidatedEvent validateObject(OntologyEventEnvelope event) {
        require(StringUtils.hasText(event.objectType()), "object_type is required");
        require(StringUtils.hasText(event.objectId()), "object_id is required");
        Map<String, PropertyContract> contract = repository.objectProperties(
                event.ontologyId(), event.objectType());
        require(!contract.isEmpty(), "Unknown object_type");
        boolean deleted = "object.delete".equals(event.eventType());
        boolean clearOverrides = "object.clear_overrides".equals(event.eventType());
        JsonNode payload = event.payload();
        if (!deleted && !clearOverrides) {
            if (!(payload instanceof ObjectNode objectPayload)) {
                throw invalid("payload must be an object");
            }
            validateProperties(objectPayload, contract,
                    event.producer().startsWith("ontology-core/action/"));
        }
        return new ValidatedEvent(event, entityKey(event.ontologyId().toString(), "object", event.objectType(), event.objectId()),
                0, filterSearchable(event.ontologyId(), event.objectType(), payload), deleted, false);
    }

    private ValidatedEvent validateRelation(OntologyEventEnvelope event) {
        require(StringUtils.hasText(event.relationType()), "relation_type is required");
        require(StringUtils.hasText(event.relationId()), "relation_id is required");
        RelationContract relation = repository.relation(event.ontologyId(), event.relationType())
                .orElseThrow(() -> invalid("Unknown relation_type"));
        String producerMode = relationProducerMode(event.producer());
        require(relation.sourceMode().equals(producerMode),
                "Relation source is " + relation.sourceMode() + " and cannot be modified by " + producerMode);
        require(relation.sourceTypeId().equals(event.sourceObjectType()), "Invalid relation source type");
        require(relation.targetTypeId().equals(event.targetObjectType()), "Invalid relation target type");
        require(StringUtils.hasText(event.sourceObjectId()), "source_object_id is required");
        require(StringUtils.hasText(event.targetObjectId()), "target_object_id is required");
        require(event.payload() == null || event.payload().isObject(), "payload must be an object");
        return new ValidatedEvent(event,
                entityKey(event.ontologyId().toString(), "relation", event.relationType(), event.relationId()),
                0,
                event.payload(),
                "relation.delete".equals(event.eventType()),
                true);
    }

    private void validateProperties(ObjectNode payload, Map<String, PropertyContract> contract, boolean partial) {
        Iterator<String> fields = payload.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            PropertyContract property = contract.get(field);
            require(property != null, "Unknown property: " + field);
            validateValue(field, payload.get(field), property.valueType());
        }
        if (!partial) {
            contract.values().stream()
                    .filter(PropertyContract::required)
                    .forEach(property -> require(
                            payload.hasNonNull(property.propertyId()),
                            "Missing required property: " + property.propertyId()));
        }
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

    public JsonNode filterSearchable(java.util.UUID ontologyId, String objectType, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return payload;
        }
        Map<String, PropertyContract> contract = repository.objectProperties(ontologyId, objectType);
        ObjectNode searchable = ((ObjectNode) payload).objectNode();
        payload.properties().forEach(entry -> {
            PropertyContract property = contract.get(entry.getKey());
            if (property != null && property.searchable() && !property.sensitive()) {
                searchable.set(entry.getKey(), entry.getValue());
            }
        });
        return searchable;
    }

    public ValidatedEvent materialize(ValidatedEvent validated, JsonNode payload, boolean deleted) {
        if (validated.relation()) return validated;
        OntologyEventEnvelope event = validated.event();
        if (!deleted) {
            if (!(payload instanceof ObjectNode objectPayload)) {
                throw invalid("payload must be an object");
            }
            validateProperties(objectPayload,
                    repository.objectProperties(event.ontologyId(), event.objectType()), false);
        }
        OntologyEventEnvelope materialized = new OntologyEventEnvelope(event.eventId(),
                deleted ? "object.delete" : "object.upsert", event.schemaVersion(),
                event.ontologyId(), event.occurredAt(), event.producer(),
                event.correlationId(), event.traceId(), event.flinkJobId(), event.objectType(),
                event.objectId(), event.relationType(), event.relationId(), event.sourceObjectType(),
                event.sourceObjectId(), event.targetObjectType(), event.targetObjectId(), payload, event.source());
        return new ValidatedEvent(materialized, validated.entityKey(), validated.projectionSequence(),
                filterSearchable(event.ontologyId(), event.objectType(), payload), deleted, false);
    }

    private String entityKey(String ontologyId, String kind, String type, String id) {
        return ontologyId + ":" + kind + ":" + type + ":" + id;
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw invalid(message);
        }
    }

    private String relationProducerMode(String producer) {
        if (producer.startsWith("ontology-core/action/")) return "MANUAL";
        if (producer.equals("ontology-core/fk-projection")) return "FOREIGN_KEY";
        if (producer.equals("ontology-flink-job")) return "PIPELINE";
        throw invalid("Unknown relation producer");
    }

    private ProjectionException invalid(String message) {
        return new ProjectionException("CONTRACT_INVALID", message, false);
    }

    public record ValidatedEvent(
            OntologyEventEnvelope event,
            String entityKey,
            long projectionSequence,
            JsonNode searchablePayload,
            boolean deleted,
            boolean relation) {
        public ValidatedEvent withProjectionSequence(long sequence) {
            return new ValidatedEvent(event, entityKey, sequence, searchablePayload, deleted, relation);
        }

    }
}
