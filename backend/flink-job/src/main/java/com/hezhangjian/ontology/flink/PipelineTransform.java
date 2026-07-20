package com.hezhangjian.ontology.flink;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;

final class PipelineTransform extends RichFlatMapFunction<String, String> {
    private final String correlationId;
    private final Map<String, Object> graph;
    private final String previewNodeId;
    private final Map<String, Object> source;
    private transient ObjectMapper json;
    private transient List<Map<String, Object>> orderedNodes;
    private transient Set<String> deduplicationKeys;

    PipelineTransform(Map<String, Object> graph, Map<String, Object> source, String correlationId) {
        this(graph, source, correlationId, null);
    }

    PipelineTransform(Map<String, Object> graph, Map<String, Object> source, String correlationId,
                      String previewNodeId) {
        this.graph = graph;
        this.source = source;
        this.correlationId = correlationId;
        this.previewNodeId = previewNodeId;
    }

    @Override
    public void open(Configuration parameters) {
        json = new ObjectMapper();
        orderedNodes = topologicalNodes();
        deduplicationKeys = new HashSet<>();
    }

    @Override
    public void flatMap(String value, Collector<String> collector) throws Exception {
        Map<String, Object> row = json.readValue(value, new TypeReference<>() { });
        List<Map<String, Object>> outputs = new ArrayList<>();
        for (Map<String, Object> node : orderedNodes) {
            String type = text(node.get("type"));
            Map<String, Object> config = map(node.get("config"));
            int outputCount = outputs.size();
            switch (type) {
                case "CAST" -> row = cast(row, config);
                case "DEDUPLICATE" -> { if (!deduplicate(row, config)) return; }
                case "DERIVE" -> row = derive(row, config);
                case "FILTER" -> { if (!matches(row, config)) return; }
                case "ONTOLOGY_OBJECT" -> outputs.add(objectEvent(row, node, config));
                case "ONTOLOGY_RELATION" -> outputs.add(relationEvent(row, node, config));
                case "QUALITY" -> { if (!qualityPasses(row, config) && "STOP".equals(config.get("failureAction"))) throw new IllegalStateException("Quality gate rejected a record"); }
                case "SELECT" -> row = select(row, config);
                default -> { }
            }
            if (previewNodeId != null && previewNodeId.equals(text(node.get("id")))) {
                if (outputs.size() > outputCount) {
                    for (int index = outputCount; index < outputs.size(); index++) {
                        collector.collect(json.writeValueAsString(outputs.get(index)));
                    }
                } else {
                    collector.collect(json.writeValueAsString(row));
                }
                return;
            }
        }
        if (previewNodeId == null) {
            for (Map<String, Object> output : outputs) collector.collect(json.writeValueAsString(output));
        }
    }

    private List<Map<String, Object>> topologicalNodes() {
        List<Map<String, Object>> nodes = listOfMaps(graph.get("nodes"));
        List<Map<String, Object>> edges = listOfMaps(graph.get("edges"));
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        nodes.forEach(node -> byId.put(text(node.get("id")), node));
        Map<String, Integer> degree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        byId.keySet().forEach(id -> degree.put(id, 0));
        edges.forEach(edge -> {
            String from = text(edge.get("source")); String to = text(edge.get("target"));
            outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(to);
            degree.computeIfPresent(to, (ignored, current) -> current + 1);
        });
        ArrayDeque<String> queue = new ArrayDeque<>();
        degree.forEach((id, current) -> { if (current == 0) queue.add(id); });
        List<Map<String, Object>> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.removeFirst(); ordered.add(byId.get(id));
            for (String target : outgoing.getOrDefault(id, List.of())) {
                int next = degree.computeIfPresent(target, (ignored, current) -> current - 1);
                if (next == 0) queue.add(target);
            }
        }
        return ordered;
    }

    private Map<String, Object> select(Map<String, Object> row, Map<String, Object> config) {
        List<Map<String, Object>> fields = listOfMaps(config.get("fields"));
        if (fields.isEmpty()) return row;
        Map<String, Object> result = new LinkedHashMap<>();
        fields.forEach(field -> {
            String from = text(field.get("source"));
            String to = field.get("target") == null ? from : text(field.get("target"));
            result.put(to, row.get(from));
        });
        return result;
    }

    private Map<String, Object> cast(Map<String, Object> row, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        map(config.get("casts")).forEach((field, type) -> {
            try { result.put(field, castValue(row.get(field), text(type))); }
            catch (RuntimeException cause) {
                if ("NULL".equals(config.get("failurePolicy"))) result.put(field, null);
                else throw cause;
            }
        });
        return result;
    }

    private Object castValue(Object value, String type) {
        if (value == null) return null;
        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "BOOLEAN" -> Boolean.valueOf(text(value));
            case "DECIMAL" -> new BigDecimal(text(value));
            case "INT", "INTEGER" -> Integer.valueOf(text(value));
            case "LONG" -> Long.valueOf(text(value));
            default -> text(value);
        };
    }

    private Map<String, Object> derive(Map<String, Object> row, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        String operation = textOr(config.get("operation"), "CONSTANT");
        Object value = switch (operation) {
            case "CONCAT" -> text(row.get(text(config.get("left")))) + textOr(config.get("separator"), "") + text(row.get(text(config.get("right"))));
            case "COALESCE" -> row.get(text(config.get("left"))) == null ? row.get(text(config.get("right"))) : row.get(text(config.get("left")));
            default -> config.get("value");
        };
        result.put(textOr(config.get("name"), "derived"), value);
        return result;
    }

    private boolean matches(Map<String, Object> row, Map<String, Object> config) {
        Object actual = row.get(text(config.get("field")));
        Object expected = config.get("value");
        return switch (textOr(config.get("operator"), "EQUALS")) {
            case "CONTAINS" -> actual != null && text(actual).contains(text(expected));
            case "GREATER_THAN" -> decimal(actual).compareTo(decimal(expected)) > 0;
            case "IS_NOT_NULL" -> actual != null;
            case "IS_NULL" -> actual == null;
            case "LESS_THAN" -> decimal(actual).compareTo(decimal(expected)) < 0;
            case "NOT_EQUALS" -> !Objects.equals(text(actual), text(expected));
            default -> Objects.equals(text(actual), text(expected));
        };
    }

    private boolean deduplicate(Map<String, Object> row, Map<String, Object> config) {
        List<String> keys = config.get("keys") instanceof List<?> values ? values.stream().map(this::text).toList() : List.of();
        if (keys.isEmpty()) return true;
        return deduplicationKeys.add(keys.stream().map(key -> text(row.get(key))).toList().toString());
    }

    private boolean qualityPasses(Map<String, Object> row, Map<String, Object> config) {
        String field = text(config.get("field"));
        return !"NOT_NULL".equals(config.get("rule")) || row.get(field) != null;
    }

    private Map<String, Object> objectEvent(Map<String, Object> row, Map<String, Object> node, Map<String, Object> config) throws Exception {
        String objectType = text(config.get("objectTypeId"));
        String objectId = text(row.get(text(config.get("idField"))));
        Map<String, Object> payload = new LinkedHashMap<>();
        map(config.get("mappings")).forEach((property, field) -> payload.put(property, row.get(text(field))));
        String rowHash = hash(json.writeValueAsBytes(row));
        Map<String, Object> event = envelope("object.upsert", node, objectType + ":" + objectId, rowHash);
        event.put("object_id", objectId);
        event.put("object_type", objectType);
        event.put("object_version", version(config, row, rowHash));
        event.put("payload", payload);
        return event;
    }

    private Map<String, Object> relationEvent(Map<String, Object> row, Map<String, Object> node, Map<String, Object> config) throws Exception {
        String relationType = text(config.get("relationTypeId"));
        String sourceId = text(row.get(text(config.get("sourceIdField"))));
        String targetId = text(row.get(text(config.get("targetIdField"))));
        String relationId = textOr(config.get("relationIdField") == null ? null : row.get(text(config.get("relationIdField"))), sourceId + ":" + targetId);
        String rowHash = hash(json.writeValueAsBytes(row));
        Map<String, Object> event = envelope("relation.upsert", node, relationType + ":" + relationId, rowHash);
        event.put("payload", Map.of());
        event.put("relation_id", relationId);
        event.put("relation_type", relationType);
        event.put("relation_version", version(config, row, rowHash));
        event.put("source_object_id", sourceId);
        event.put("source_object_type", text(config.get("sourceObjectTypeId")));
        event.put("target_object_id", targetId);
        event.put("target_object_type", text(config.get("targetObjectTypeId")));
        return event;
    }

    private Map<String, Object> envelope(String eventType, Map<String, Object> node, String key, String rowHash) {
        String stable = source.get("pipelineId") + ":" + source.get("pipelineVersion") + ":" + node.get("id") + ":" + key + ":" + rowHash;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("correlation_id", correlationId);
        event.put("event_id", UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)));
        event.put("event_type", eventType);
        event.put("flink_job_id", getRuntimeContext().getJobId().toString());
        event.put("occurred_at", Instant.now().toString());
        event.put("ontology_revision", 1);
        event.put("producer", "ontology-flink-job");
        event.put("schema_version", 1);
        event.put("source", Map.of("asset_id", source.get("assetId"), "data_source_id", source.get("connectionId"), "pipeline_run_id", source.get("runId")));
        return event;
    }

    private long version(Map<String, Object> config, Map<String, Object> row, String rowHash) {
        Object configured = config.get("versionField") == null ? null : row.get(text(config.get("versionField")));
        if (configured instanceof Number number) return Math.max(1, number.longValue());
        try { return Math.max(1, Long.parseLong(text(configured))); }
        catch (NumberFormatException ignored) { return Math.max(1, Long.parseUnsignedLong(rowHash.substring(0, 15), 16)); }
    }

    private BigDecimal decimal(Object value) { return new BigDecimal(text(value)); }
    private String hash(byte[] value) throws Exception { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private String textOr(Object value, String fallback) { String text = text(value); return text.isBlank() ? fallback : text; }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? new LinkedHashMap<>((Map<String, Object>) raw) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> raw ? raw.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList() : List.of();
    }
}
