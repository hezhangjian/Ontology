package com.hezhangjian.ontology.instance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ObjectInstanceModels {
    private ObjectInstanceModels() {}

    public record PropertySchema(
            UUID id,
            String apiName,
            String displayName,
            String physicalKey,
            String valueType,
            boolean required,
            boolean primaryKey,
            boolean titleProperty,
            boolean searchable,
            boolean sensitive) {}

    public record ObjectSchema(
            UUID ontologyId,
            UUID objectTypeId,
            String objectTypeApiName,
            String objectTypePhysicalKey,
            PropertySchema primaryKey,
            PropertySchema titleProperty,
            List<PropertySchema> properties,
            String schemaName,
            String tableName) {
        public Map<String, PropertySchema> propertiesByApiName() {
            return properties.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    PropertySchema::apiName, property -> property));
        }

        public Map<String, PropertySchema> propertiesByDisplayName() {
            Map<String, PropertySchema> values = new java.util.LinkedHashMap<>();
            for (PropertySchema property : properties) {
                PropertySchema duplicate = values.put(property.displayName(), property);
                if (duplicate != null) {
                    throw new ObjectInstanceStoreException(
                            "PROPERTY_DISPLAY_NAME_CONFLICT",
                            "Property display names must be unique: " + property.displayName());
                }
            }
            return Map.copyOf(values);
        }

        public PropertySchema property(String name) {
            if (name == null) {
                return null;
            }
            PropertySchema property = propertiesByDisplayName().get(name);
            return property == null ? propertiesByApiName().get(name) : property;
        }
    }

    public record StoredInstance(
            String id,
            String title,
            long version,
            JsonNode basePayload,
            JsonNode overridePayload,
            String sourceKind,
            String sourceRef,
            String sourceRevision,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}

    public record MutationResult(StoredInstance instance, UUID correlationId, UUID eventId) {}

    public record InstancePage(List<StoredInstance> items, String nextCursor) {}

    public record QueryFilter(String propertyId, String operator, Object value) {}

    public record QuerySort(String propertyId, String direction) {}

    public record AggregateMetric(String operation, String propertyId, String alias) {}

    public record ProjectionStatus(
            String target, long projectedVersion, String status, String lastError, Instant updatedAt) {}
}
