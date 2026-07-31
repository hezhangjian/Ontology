package com.hezhangjian.ontology.service;

import com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.model.CreateObjectInstanceImportReq;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DatasetObjectRowMapper {
    public void validate(ObjectSchema schema, CreateObjectInstanceImportReq request) {
        if (request.getFieldMappings() == null || request.getFieldMappings().isEmpty()) {
            throw new ObjectInstanceStoreException(
                    "IMPORT_MAPPING_REQUIRED", "At least one field mapping is required");
        }
        if (request.getFieldMappings().values().stream()
                .anyMatch(name -> schema.property(name) == null)) {
            throw new ObjectInstanceStoreException(
                    "IMPORT_MAPPING_INVALID", "Mapping references an unknown object property");
        }
        if (!schema.primaryKey().equals(schema.property(
                request.getFieldMappings().get(request.getIdentityField())))) {
            throw new ObjectInstanceStoreException(
                    "IMPORT_ID_MAPPING_INVALID",
                    "Identity field must map to the primary key property");
        }
        if (!schema.titleProperty().equals(schema.property(
                request.getFieldMappings().get(request.getTitleField())))) {
            throw new ObjectInstanceStoreException(
                    "IMPORT_TITLE_MAPPING_INVALID",
                    "Title field must map to the title property");
        }
    }

    public Map<String, Object> map(
            ObjectSchema schema,
            Map<String, String> fieldMappings,
            Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        fieldMappings.forEach((source, target) -> {
            if (row.containsKey(source)) {
                var property = schema.property(target);
                result.put(
                        property.apiName(),
                        coerce(
                                property.valueType(),
                                row.get(source)));
            }
        });
        return Map.copyOf(result);
    }

    private Object coerce(String valueType, Object value) {
        if (value == null || !(value instanceof String text)) {
            return value;
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return switch (valueType) {
                case "BOOLEAN" -> booleanValue(normalized);
                case "DECIMAL" -> new BigDecimal(normalized);
                case "INTEGER" -> Integer.valueOf(normalized);
                case "LONG" -> Long.valueOf(normalized);
                default -> value;
            };
        } catch (RuntimeException failure) {
            throw new ObjectInstanceStoreException(
                    "IMPORT_CONVERSION_INVALID",
                    "Dataset value cannot be converted to " + valueType,
                    failure);
        }
    }

    private boolean booleanValue(String value) {
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException("invalid boolean");
    }
}
