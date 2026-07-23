package com.hezhangjian.ontology.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
final class OntologyToolClient {
    private final ObjectMapper json;
    private final WebClient web;

    OntologyToolClient(WebClient.Builder builder, ObjectMapper json,
                       @Value("${agent.ontology-core-url:http://ontology-core:8000}") String coreUrl) {
        this.json = json;
        this.web = builder.baseUrl(coreUrl).build();
    }

    Object execute(String name, Map<String, Object> arguments, RequestContext context) {
        return switch (name) {
            case "aggregate_objects" -> post("/v1/object-sets/aggregate", aggregateBody(arguments), context);
            case "facet_objects" -> post("/v1/object-sets/facets", Map.of(
                    "propertyIds", list(arguments.get("propertyIds")), "query", query(arguments)), context);
            case "get_object" -> get("/v1/objects/" + required(arguments, "objectTypeId") + "/"
                    + encode(required(arguments, "objectId")), context);
            case "get_pipeline" -> get("/v1/pipelines/" + required(arguments, "pipelineId"), context);
            case "execute_function" -> executeFunction(arguments, context);
            case "list_actions" -> get("/v1/modeling/actions", context);
            case "list_functions" -> get("/v1/modeling/functions", context);
            case "list_link_types" -> get("/v1/modeling/link-types", context);
            case "list_object_types" -> compactObjectTypes(get("/v1/modeling/object-types", context));
            case "list_pipelines" -> get("/v1/pipelines?page=0&size=100", context);
            case "preview_action" -> post("/v1/modeling/actions/" + required(arguments, "actionId") + "/preview",
                    previewActionBody(arguments), context);
            case "query_objects" -> compactQuery(post("/v1/object-sets/query", query(arguments), context), arguments);
            case "traverse_relations" -> post("/v1/object-sets/search-around", Map.of(
                    "linkTypeIds", list(arguments.get("linkTypeIds")),
                    "objectId", required(arguments, "objectId"),
                    "objectTypeId", required(arguments, "objectTypeId"), "pageSize", 100), context);
            case "design_rule_transform" -> designRule(arguments);
            default -> throw new IllegalArgumentException("Unknown agent tool: " + name);
        };
    }

    Object confirmAction(Map<String, Object> request, RequestContext context) {
        return post("/v1/modeling/actions/" + required(request, "actionId") + "/execute", Map.of(
                "idempotencyKey", required(request, "idempotencyKey"),
                "previewToken", required(request, "previewToken")), context);
    }

    @SuppressWarnings("unchecked")
    Object confirmRuleTransform(Map<String, Object> proposal, RequestContext context) {
        Map<String, Object> pipeline = (Map<String, Object>) get("/v1/pipelines/" + required(proposal, "pipelineId"), context);
        Map<String, Object> draft = map(pipeline.get("draft"));
        Map<String, Object> graph = map(draft.get("graph"));
        List<Map<String, Object>> nodes = json.convertValue(graph.get("nodes"), new TypeReference<>() { });
        List<Map<String, Object>> edges = json.convertValue(graph.get("edges"), new TypeReference<>() { });
        String beforeNodeId = required(proposal, "beforeNodeId");
        List<Map<String, Object>> incoming = edges.stream().filter(edge -> beforeNodeId.equals(String.valueOf(edge.get("target")))).toList();
        if (incoming.size() != 1) throw new IllegalArgumentException("目标节点必须恰好有一条输入连线");
        String ruleNodeId = "rule-" + java.util.UUID.randomUUID();
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("action", "REPLACE");
        rule.put("field", required(proposal, "field"));
        rule.put("max", proposal.get("max"));
        rule.put("min", proposal.get("min"));
        rule.put("operator", proposal.getOrDefault("operator", "OUTSIDE_RANGE"));
        rule.put("preserveOriginalAs", proposal.getOrDefault("preserveOriginalAs", "raw_" + required(proposal, "field")));
        rule.put("replacement", proposal.get("replacement"));
        rule.put("statusField", proposal.getOrDefault("statusField", "cleaning_status"));
        Map<String, Object> target = nodes.stream().filter(node -> beforeNodeId.equals(String.valueOf(node.get("id")))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标节点不存在"));
        Map<String, Object> position = map(target.get("position"));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("config", Map.of("rules", List.of(rule)));
        node.put("id", ruleNodeId);
        node.put("inputSchema", List.of());
        node.put("invalidReasons", List.of());
        node.put("name", "规则清洗");
        node.put("outputSchema", List.of());
        node.put("position", Map.of("x", number(position.get("x")) - 180, "y", number(position.get("y"))));
        node.put("type", "RULE_TRANSFORM");
        List<Map<String, Object>> updatedNodes = new java.util.ArrayList<>(nodes);
        updatedNodes.add(node);
        List<Map<String, Object>> updatedEdges = new java.util.ArrayList<>(edges);
        Map<String, Object> old = incoming.getFirst();
        updatedEdges.remove(old);
        updatedEdges.add(Map.of("id", "edge-" + java.util.UUID.randomUUID(), "source", old.get("source"), "target", ruleNodeId));
        updatedEdges.add(Map.of("id", "edge-" + java.util.UUID.randomUUID(), "source", ruleNodeId, "target", beforeNodeId));
        Object updated = patch("/v1/pipelines/" + required(proposal, "pipelineId") + "/draft",
                Map.of("graph", Map.of("edges", updatedEdges, "nodes", updatedNodes)),
                Map.of("If-Match", String.valueOf(draft.get("etag"))), context);
        return Map.of("pipeline", updated, "ruleNodeId", ruleNodeId, "status", "DRAFT_UPDATED");
    }

    private Object designRule(Map<String, Object> arguments) {
        for (String key : List.of("beforeNodeId", "field", "max", "min", "pipelineId", "replacement")) required(arguments, key);
        Map<String, Object> result = new LinkedHashMap<>(arguments);
        result.put("kind", "RULE_TRANSFORM_PREVIEW");
        result.put("requiresConfirmation", true);
        return result;
    }

    private Object executeFunction(Map<String, Object> arguments, RequestContext context) {
        String functionId = required(arguments, "functionId");
        Map<String, Object> function = map(get("/v1/modeling/functions/" + functionId, context));
        Map<String, Object> definition = map(function.get("definition"));
        Map<String, Object> dsl = map(definition.get("queryDsl"));
        Map<String, Object> inputs = map(arguments.get("inputs"));
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (Object rawStep : list(dsl.get("steps"))) {
            Map<String, Object> step = map(rawStep);
            String id = required(step, "id");
            String tool = required(step, "tool");
            if (!List.of("aggregate_objects", "facet_objects", "get_object", "query_objects", "traverse_relations").contains(tool)) {
                throw new IllegalArgumentException("Function DSL tool is not read-only or supported: " + tool);
            }
            Map<String, Object> resolved = map(resolve(step.get("arguments"), inputs, outputs));
            outputs.put(id, execute(tool, resolved, context));
        }
        Object result = dsl.containsKey("result") ? resolve(dsl.get("result"), inputs, outputs) : outputs;
        return Map.of("functionId", functionId, "functionVersion", function.get("version"),
                "name", function.get("displayName"), "result", result, "steps", outputs);
    }

    private Object resolve(Object value, Map<String, Object> inputs, Map<String, Object> outputs) {
        if (value instanceof String text && text.startsWith("$")) {
            if (text.startsWith("$inputs.")) return path(inputs, text.substring("$inputs.".length()));
            if (text.startsWith("$steps.")) return path(outputs, text.substring("$steps.".length()));
            return text;
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> resolved = new LinkedHashMap<>();
            raw.forEach((key, item) -> resolved.put(String.valueOf(key), resolve(item, inputs, outputs)));
            return resolved;
        }
        if (value instanceof List<?> raw) return raw.stream().map(item -> resolve(item, inputs, outputs)).toList();
        return value;
    }

    private Object path(Object root, String path) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> values) current = values.get(segment);
            else if (current instanceof List<?> values) current = values.get(Integer.parseInt(segment));
            else throw new IllegalArgumentException("Function DSL reference cannot resolve: " + path);
        }
        if (current == null) throw new IllegalArgumentException("Function DSL reference is missing: " + path);
        return current;
    }

    private Map<String, Object> query(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", list(arguments.get("columns")));
        result.put("objectTypeId", required(arguments, "objectTypeId"));
        result.put("pageSize", 25);
        result.put("sort", sorts(arguments.get("sort")));
        result.put("where", where(arguments));
        return result;
    }

    private Map<String, Object> where(Map<String, Object> arguments) {
        if (arguments.get("filters") instanceof List<?> filters && !filters.isEmpty()) {
            List<Map<String, Object>> children = filters.stream().map(this::filterNode).toList();
            return Map.of("children", children, "type", "and");
        }
        return map(arguments.get("where"));
    }

    private Map<String, Object> filterNode(Object value) {
        Map<String, Object> filter = map(value);
        Map<String, Object> node = new LinkedHashMap<>();
        String operator = required(filter, "operator").toLowerCase(java.util.Locale.ROOT);
        Object filterValue = filter.get("value");
        if ("recent_days".equals(operator)) {
            int days = filterValue instanceof Number number ? Math.max(1, number.intValue()) : 7;
            operator = "gte";
            filterValue = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                    .minusDays(days - 1L).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toOffsetDateTime().toString();
        }
        node.put("operator", operator);
        node.put("propertyId", required(filter, "propertyId"));
        node.put("type", "property");
        node.put("value", filterValue);
        return node;
    }

    private List<Map<String, Object>> sorts(Object value) {
        return list(value).stream().map(raw -> {
            Map<String, Object> sort = map(raw);
            return Map.<String, Object>of(
                    "direction", required(sort, "direction").toLowerCase(java.util.Locale.ROOT),
                    "propertyId", required(sort, "propertyId"));
        }).toList();
    }

    private Object compactObjectTypes(Object value) {
        if (!(value instanceof List<?> values)) return value;
        return values.stream().map(this::compactObjectType).toList();
    }

    private Map<String, Object> compactObjectType(Object value) {
        Map<String, Object> type = map(value);
        Map<String, Object> definition = map(type.get("definition"));
        List<Map<String, Object>> properties = list(type.get("properties")).stream().map(item -> {
            Map<String, Object> property = map(item);
            Map<String, Object> compact = new LinkedHashMap<>();
            for (String key : List.of("apiName", "displayName", "filterable", "id", "sortable", "valueType")) {
                if (property.containsKey(key)) compact.put(key, property.get(key));
            }
            return compact;
        }).toList();
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("apiName", "description", "displayName", "id", "lifecycle")) {
            if (type.containsKey(key)) compact.put(key, type.get(key));
        }
        if (definition.containsKey("primaryPipelineId")) compact.put("primaryPipelineId", definition.get("primaryPipelineId"));
        compact.put("properties", properties);
        return compact;
    }

    private Object compactQuery(Object value, Map<String, Object> arguments) {
        Map<String, Object> page = map(value);
        if (page.isEmpty()) return value;
        Map<String, String> propertyNames = new LinkedHashMap<>();
        for (Object item : list(page.get("properties"))) {
            Map<String, Object> property = map(item);
            propertyNames.put(String.valueOf(property.get("id")), String.valueOf(property.get("apiName")));
        }
        List<?> requested = list(arguments.get("columns"));
        java.util.Set<String> selected = requested.isEmpty()
                ? new java.util.LinkedHashSet<>(propertyNames.values())
                : requested.stream().map(String::valueOf).map(propertyNames::get).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        List<Map<String, Object>> items = list(page.get("items")).stream().map(raw -> {
            Map<String, Object> item = map(raw);
            Map<String, Object> properties = map(item.get("properties"));
            Map<String, Object> chosen = new LinkedHashMap<>();
            selected.forEach(name -> { if (properties.containsKey(name)) chosen.put(name, properties.get(name)); });
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("objectId", item.get("objectId"));
            compact.put("properties", chosen);
            compact.put("title", item.get("title"));
            return compact;
        }).toList();
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : List.of("objectTypeId", "objectTypeName", "ontologyRevision", "visibleCount")) {
            if (page.containsKey(key)) compact.put(key, page.get(key));
        }
        compact.put("items", items);
        return compact;
    }

    private Map<String, Object> aggregateBody(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aggregation", arguments.getOrDefault("aggregation", "count"));
        result.put("dimensionPropertyIds", list(arguments.get("dimensionPropertyIds")));
        if (arguments.get("divisorPropertyId") != null) result.put("divisorPropertyId", arguments.get("divisorPropertyId"));
        if (arguments.get("measurePropertyId") != null) result.put("measurePropertyId", arguments.get("measurePropertyId"));
        result.put("query", query(arguments));
        return result;
    }

    private Map<String, Object> previewActionBody(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("objectId", required(arguments, "objectId"));
        if (arguments.get("objectVersion") != null) result.put("objectVersion", arguments.get("objectVersion"));
        result.put("parameters", map(arguments.get("parameters")));
        return result;
    }

    private Object get(String path, RequestContext context) { return request("GET", path, null, Map.of(), context); }
    private Object post(String path, Object body, RequestContext context) { return request("POST", path, body, Map.of(), context); }
    private Object patch(String path, Object body, Map<String, String> headers, RequestContext context) { return request("PATCH", path, body, headers, context); }

    private Object request(String method, String path, Object body, Map<String, String> headers, RequestContext context) {
        WebClient.RequestBodySpec request = web.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("X-Ontology-Id", context.ontologyId()).header("X-Workspace-Id", context.ontologyId());
        if (context.authorization() != null && !context.authorization().isBlank()) request.header(HttpHeaders.AUTHORIZATION, context.authorization());
        headers.forEach(request::header);
        WebClient.ResponseSpec response = (body == null ? request.retrieve() : request.bodyValue(body).retrieve())
                .onStatus(status -> status.isError(), failed -> failed.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(error -> new IllegalStateException(failed.statusCode() + " " + truncate(error, 1200))));
        return response.bodyToMono(Object.class).block(Duration.ofSeconds(30));
    }

    private String truncate(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    private String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("Missing tool argument: " + key);
        return String.valueOf(value);
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private List<?> list(Object value) { return value instanceof List<?> list ? list : List.of(); }
    private double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0; }
    private String encode(String value) { return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8); }

    record RequestContext(String authorization, String ontologyId) { }
}
