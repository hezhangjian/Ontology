package com.hezhangjian.ontology.flink;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.util.Collector;

final class PipelineTransform extends RichFlatMapFunction<String, String> implements CheckpointedFunction {
    private final String correlationId;
    private final Map<String, Object> graph;
    private final String previewNodeId;
    private final Map<String, Object> source;
    private transient ObjectMapper json;
    private transient List<Map<String, Object>> orderedNodes;
    private transient long datasetRowNumber;
    private transient ListState<Long> rowNumberState;

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
                case "DERIVE" -> row = derive(row, config);
                case "FILTER" -> { if (!matches(row, config)) return; }
                case "OBJECT_OUTPUT" -> outputs.add(objectEvent(row, node, config));
                case "LINK_OUTPUT" -> outputs.add(relationEvent(row, node, config));
                case "DATASET_OUTPUT" -> outputs.add(datasetEvent(row, node, config));
                case "QUALITY" -> { if (!qualityPasses(row, config) && "STOP".equals(config.get("failureAction"))) throw new IllegalStateException("Quality gate rejected a record"); }
                case "RULE_TRANSFORM" -> {
                    row = ruleTransform(row, config);
                    if (row == null) return;
                }
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

    private Map<String, Object> ruleTransform(Map<String, Object> row, Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        Object rawRules = config.get("rules");
        if (!(rawRules instanceof List<?> rules)) return result;
        for (Object rawRule : rules) {
            if (!(rawRule instanceof Map<?, ?> untyped)) continue;
            Map<String, Object> rule = new LinkedHashMap<>();
            untyped.forEach((key, value) -> rule.put(String.valueOf(key), value));
            String field = text(rule.get("field"));
            Object actual = result.get(field);
            if (!ruleMatches(actual, rule)) continue;
            String action = textOr(rule.get("action"), "REPLACE");
            if ("DROP".equals(action)) return null;
            if ("KEEP".equals(action)) continue;
            if ("QUARANTINE".equals(action)) {
                result.put(textOr(rule.get("statusField"), "_quality_status"), "QUARANTINED");
                continue;
            }
            String originalField = text(rule.get("preserveOriginalAs"));
            if (!originalField.isBlank()) result.putIfAbsent(originalField, actual);
            result.put(field, rule.get("replacement"));
            String statusField = text(rule.get("statusField"));
            if (!statusField.isBlank()) result.put(statusField, "CLEANED");
        }
        return result;
    }

    private boolean ruleMatches(Object actual, Map<String, Object> rule) {
        return switch (text(rule.get("operator"))) {
            case "EQUALS" -> Objects.equals(text(actual), text(rule.get("value")));
            case "GREATER_THAN" -> compareNumbers(actual, rule.get("value"), comparison -> comparison > 0);
            case "IS_NULL" -> actual == null || text(actual).isBlank();
            case "LESS_THAN" -> compareNumbers(actual, rule.get("value"), comparison -> comparison < 0);
            case "OUTSIDE_RANGE" -> compareNumbers(actual, rule.get("min"), comparison -> comparison < 0)
                    || compareNumbers(actual, rule.get("max"), comparison -> comparison > 0);
            default -> false;
        };
    }

    private boolean compareNumbers(Object left, Object right, java.util.function.IntPredicate predicate) {
        BigDecimal leftNumber = decimalOrNull(left);
        BigDecimal rightNumber = decimalOrNull(right);
        return leftNumber != null && rightNumber != null && predicate.test(leftNumber.compareTo(rightNumber));
    }

    private BigDecimal decimalOrNull(Object value) {
        if (value == null || text(value).isBlank()) return null;
        try { return decimal(value); }
        catch (NumberFormatException ignored) { return null; }
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

    private boolean qualityPasses(Map<String, Object> row, Map<String, Object> config) {
        String field = text(config.get("field"));
        return !"NOT_NULL".equals(config.get("rule")) || row.get(field) != null;
    }

    private Map<String, Object> objectEvent(Map<String, Object> row, Map<String, Object> node, Map<String, Object> config) throws Exception {
        String objectType = text(config.get("objectTypeId"));
        List<String> idFields = config.get("idFields") instanceof List<?> values
                ? values.stream().map(this::text).filter(field -> !field.isBlank()).toList()
                : List.of(text(config.get("idField")));
        Map<String, Object> identity = new LinkedHashMap<>();
        idFields.forEach(field -> identity.put(field, row.get(field)));
        String objectId = idFields.size() == 1 ? text(identity.get(idFields.get(0)))
                : hash(json.writeValueAsBytes(identity));
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> propertyTypes = map(config.get("propertyTypes"));
        map(config.get("mappings")).forEach((property, field) -> {
            String sourceField = text(field);
            payload.put(property, typedValue(row.get(sourceField),
                    textOr(propertyTypes.get(property), schemaType(node, sourceField))));
        });
        String primaryPropertyKey = text(config.get("primaryPropertyKey"));
        if (!primaryPropertyKey.isBlank()) {
            payload.putIfAbsent(primaryPropertyKey, objectId);
        }
        String payloadHash = hash(json.writeValueAsBytes(payload));
        Map<String, Object> event = envelope("object.upsert", node, objectType + ":" + objectId, payloadHash);
        event.put("object_id", objectId);
        event.put("object_type", objectType);
        event.put("object_version", version(config, row, payloadHash));
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

    private Map<String, Object> datasetEvent(Map<String, Object> row, Map<String, Object> node,
                                             Map<String, Object> config) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        List<Map<String, Object>> mappings = listOfMaps(config.get("fieldMappings"));
        boolean explicitSelection = "EXPLICIT".equals(text(config.get("fieldSelectionMode")));
        if (mappings.isEmpty() && !explicitSelection) payload.putAll(row);
        else mappings.forEach(mapping -> {
            String sourceField = text(mapping.get("source"));
            String targetField = text(mapping.get("target"));
            if (!sourceField.isBlank() && !targetField.isBlank()) payload.put(targetField, row.get(sourceField));
        });
        String rowHash = hash(json.writeValueAsBytes(payload));
        long rowNumber = ++datasetRowNumber;
        int subtask;
        try {
            subtask = getRuntimeContext().getIndexOfThisSubtask();
        } catch (IllegalStateException outsideFlinkRuntime) {
            subtask = 0;
        }
        Map<String, Object> event = envelope("dataset.row", node,
                text(config.get("datasetId")) + ":" + subtask + ":" + rowNumber,
                rowHash + ":" + subtask + ":" + rowNumber);
        event.put("dataset_id", text(config.get("datasetId")));
        event.put("dataset_name", text(config.get("datasetName")));
        event.put("payload", payload);
        event.put("row_number", rowNumber);
        event.put("source_subtask", subtask);
        return event;
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        rowNumberState.clear();
        rowNumberState.add(datasetRowNumber);
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        rowNumberState = context.getOperatorStateStore()
                .getListState(new ListStateDescriptor<>("dataset-row-number", Long.class));
        if (context.isRestored()) {
            for (Long value : rowNumberState.get()) datasetRowNumber = Math.max(datasetRowNumber, value);
        }
    }

    private Map<String, Object> envelope(String eventType, Map<String, Object> node, String key, String rowHash) {
        String stable = correlationId + ":" + source.get("pipelineId") + ":" + source.get("pipelineVersion")
                + ":" + node.get("id") + ":" + key + ":" + rowHash;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("correlation_id", correlationId);
        event.put("event_id", UUID.nameUUIDFromBytes(stable.getBytes(StandardCharsets.UTF_8)));
        event.put("event_type", eventType);
        event.put("flink_job_id", flinkJobId());
        event.put("occurred_at", Instant.now().toString());
        event.put("ontology_revision", number(source.get("ontologyRevision"), 1));
        event.put("ontology_id", source.get("ontologyId"));
        event.put("producer", "ontology-flink-job");
        event.put("schema_version", 1);
        event.put("source", Map.of("asset_id", source.get("assetId"), "data_source_id", source.get("connectionId"), "pipeline_run_id", source.get("runId")));
        return event;
    }

    private String flinkJobId() {
        try { return getRuntimeContext().getJobId().toString(); }
        catch (IllegalStateException ignored) { return textOr(source.get("flinkJobId"), "local"); }
    }

    private long version(Map<String, Object> config, Map<String, Object> row, String rowHash) {
        Object configured = config.get("versionField") == null ? null : row.get(text(config.get("versionField")));
        if (configured instanceof Number number) return Math.max(1, number.longValue());
        try { return Math.max(1, Long.parseLong(text(configured))); }
        catch (NumberFormatException ignored) { return Math.max(1, Long.parseUnsignedLong(rowHash.substring(0, 15), 16)); }
    }

    private long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String schemaType(Map<String, Object> node, String field) {
        return listOfMaps(node.get("inputSchema")).stream()
                .filter(item -> field.equals(text(item.get("name"))))
                .map(item -> text(item.get("type"))).findFirst().orElse("TEXT");
    }

    private Object typedValue(Object value, String type) {
        if (value == null || text(value).isBlank()) return null;
        return switch (type) {
            case "BOOLEAN" -> value instanceof Boolean ? value : Boolean.parseBoolean(text(value));
            case "DECIMAL", "DOUBLE", "FLOAT" -> value instanceof Number ? value : decimal(value);
            case "INT", "INTEGER", "LONG" -> value instanceof Number number
                    ? number.longValue()
                    : Long.parseLong(text(value));
            case "JSON" -> value instanceof Map<?, ?> || value instanceof List<?> ? value : parseJson(value);
            case "DATE", "DATETIME", "ENUM", "STRING", "TEXT" -> text(value);
            default -> value;
        };
    }

    private Object parseJson(Object value) {
        try { return json.readValue(text(value), Object.class); }
        catch (Exception ignored) { return value; }
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
