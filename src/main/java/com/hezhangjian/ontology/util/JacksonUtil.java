package com.hezhangjian.ontology.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public final class JacksonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JacksonUtil() {}

    public static String toJson(Object object) throws JsonProcessingException {
        return MAPPER.writeValueAsString(object);
    }

    public static <T> T toObject(String json, Class<T> type) throws JsonProcessingException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return MAPPER.readValue(json, type);
    }

    public static <T> T toRefer(String json, TypeReference<T> typeReference) throws JsonProcessingException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        return MAPPER.readValue(json, typeReference);
    }

    public static <T> List<T> toList(String json, TypeReference<List<T>> typeReference) throws JsonProcessingException {
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        return MAPPER.readValue(json, typeReference);
    }

    public static JsonNode toJsonNode(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }

    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }

    public static ArrayNode createArrayNode() {
        return MAPPER.createArrayNode();
    }
}
