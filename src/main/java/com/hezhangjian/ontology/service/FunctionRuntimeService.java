package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.service.ExplorerModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import org.springframework.stereotype.Service;

@Service
public class FunctionRuntimeService {
    private static final int MAX_CALL_DEPTH = 8;
    private final ExplorerService explorer;
    private final SqlClientRepository jdbc;
    private final ObjectMapper json;

    public FunctionRuntimeService(ExplorerService explorer, SqlClientRepository jdbc, ObjectMapper json) {
        this.explorer = explorer;
        this.jdbc = jdbc;
        this.json = json;
    }

    public Object execute(Map<String, Object> dsl, Map<String, Object> inputs) {
        return executeDetailed(dsl, inputs).result();
    }

    public RuntimeResult executeDetailed(
            Map<String, Object> dsl,
            Map<String, Object> inputs) {
        List<Map<String, Object>> diagnostics = new java.util.ArrayList<>();
        Object result = execute(dsl == null ? Map.of() : dsl,
                inputs == null ? Map.of() : inputs,
                new Actor("local", "Local", List.of("Admin", "Builder", "Viewer")),
                0, diagnostics);
        return new RuntimeResult(result, List.copyOf(diagnostics));
    }

    private Object execute(Map<String, Object> dsl, Map<String, Object> inputs,
                           Actor caller, int depth, List<Map<String, Object>> diagnostics) {
        if (depth >= MAX_CALL_DEPTH) {
            throw new IllegalArgumentException("Function call depth exceeded");
        }
        if (!dsl.containsKey("steps")) {
            UUID objectTypeId = UUID.fromString(Objects.toString(dsl.get("fromObjectTypeId")));
            Integer limit = number(dsl.get("limit"), 100);
            return explorer.query(new ObjectSetRequest(objectTypeId, map(dsl.get("where")),
                    List.of(), limit, null, List.of()), caller);
        }
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Object raw : list(dsl.get("steps"))) {
            Map<String, Object> step = map(raw);
            String id = Objects.toString(step.get("id"), "");
            String operation = Objects.toString(
                    step.containsKey("operation") ? step.get("operation") : step.get("tool"), "");
            Map<String, Object> arguments = map(resolve(step.get("arguments"), inputs, outputs));
            long started = System.nanoTime();
            try {
                Object value = executeOperation(
                        operation, arguments, caller, depth, diagnostics);
                outputs.put(id, value);
                diagnostics.add(Map.of(
                        "durationMs", Math.max(1, (System.nanoTime() - started) / 1_000_000),
                        "operation", operation.toUpperCase(),
                        "status", "SUCCEEDED",
                        "stepId", id));
            } catch (RuntimeException failure) {
                diagnostics.add(Map.of(
                        "durationMs", Math.max(1, (System.nanoTime() - started) / 1_000_000),
                        "operation", operation.toUpperCase(),
                        "safeError", safe(failure.getMessage()),
                        "status", "FAILED",
                        "stepId", id));
                throw failure;
            }
        }
        return dsl.containsKey("result") ? resolve(dsl.get("result"), inputs, outputs) : outputs;
    }

    private Object executeOperation(
            String operation,
            Map<String, Object> arguments,
            Actor caller,
            int depth,
            List<Map<String, Object>> diagnostics) {
        return switch (operation.toUpperCase()) {
            case "AGGREGATE_OBJECT_SET", "AGGREGATE_OBJECTS" ->
                    explorer.aggregate(json.convertValue(arguments, AggregateRequest.class), caller);
            case "FACET_OBJECT_SET", "FACET_OBJECTS" ->
                    explorer.facets(json.convertValue(arguments, FacetRequest.class), caller);
            case "GET_OBJECT" -> explorer.object(
                    uuid(arguments, "objectTypeId"), text(arguments, "objectId"), caller);
            case "QUERY_OBJECT_SET", "QUERY_OBJECTS" ->
                    explorer.query(json.convertValue(queryBody(arguments), ObjectSetRequest.class), caller);
            case "TRAVERSE_LINKS", "TRAVERSE_RELATIONS" -> explorer.links(
                    uuid(arguments, "objectTypeId"), text(arguments, "objectId"),
                    new LinkRequest(Objects.toString(arguments.getOrDefault("direction", "BOTH")),
                            json.convertValue(arguments.getOrDefault("linkTypeIds", List.of()),
                                    json.getTypeFactory().constructCollectionType(List.class, UUID.class)),
                            number(arguments.get("pageSize"), 100), null), caller);
            case "CALL_FUNCTION" -> execute(functionDsl(uuid(arguments, "functionId")),
                    map(arguments.get("inputs")), caller, depth + 1, diagnostics);
            default -> throw new IllegalArgumentException("Unsupported Function operation: " + operation);
        };
    }

    private Map<String, Object> functionDsl(UUID functionId) {
        String value = jdbc.sql("""
                SELECT fv.query_dsl::text
                FROM control.ontology_resources r
                JOIN control.ontology_resource_versions rv
                  ON rv.resource_id=r.id AND rv.version=r.active_version
                JOIN control.function_type_versions fv ON fv.version_id=rv.id
                WHERE r.id=:id AND r.ontology_id=:ontology
                  AND r.kind='FUNCTION'
                """).param("id", functionId).param("ontology", WorkspaceContext.id())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Called Function is not published in the active ontology"));
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (Exception failure) {
            throw new IllegalArgumentException("Called Function DSL is invalid", failure);
        }
    }

    private Object queryBody(Map<String, Object> arguments) {
        return arguments.getOrDefault("query", arguments);
    }

    private Object resolve(Object value, Map<String, Object> inputs, Map<String, Object> outputs) {
        if (value instanceof String text && text.startsWith("$")) {
            if (text.startsWith("$inputs.")) return path(inputs, text.substring(8));
            if (text.startsWith("$steps.")) return path(outputs, text.substring(7));
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            raw.forEach((key, item) -> resolved.put(String.valueOf(key), resolve(item, inputs, outputs)));
            return resolved;
        }
        if (value instanceof List<?> raw) return raw.stream()
                .map(item -> resolve(item, inputs, outputs)).toList();
        return value;
    }

    private Object path(Object root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> values) current = values.get(segment);
            else if (current instanceof List<?> values) current = values.get(Integer.parseInt(segment));
            else throw new IllegalArgumentException("Function reference cannot resolve: " + path);
        }
        if (current == null) throw new IllegalArgumentException("Function reference is missing: " + path);
        return current;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
    }

    private List<?> list(Object value) {
        return value instanceof List<?> values ? values : List.of();
    }

    private String text(Map<String, Object> values, String key) {
        String value = Objects.toString(values.get(key), "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private UUID uuid(Map<String, Object> values, String key) {
        return UUID.fromString(text(values, key));
    }

    private Integer number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private String safe(String message) {
        String value = Objects.toString(message, "Function step failed")
                .replaceAll("[\\r\\n]+", " ");
        return value.substring(0, Math.min(500, value.length()));
    }

    public record RuntimeResult(Object result, List<Map<String, Object>> diagnostics) {
    }
}
