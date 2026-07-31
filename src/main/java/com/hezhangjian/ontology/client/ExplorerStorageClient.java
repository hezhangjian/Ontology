package com.hezhangjian.ontology.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.config.HugeGraphProperties;
import com.hezhangjian.ontology.config.OpenSearchProperties;
import com.hezhangjian.ontology.service.ExplorerModels.Actor;
import com.hezhangjian.ontology.service.ExplorerModels.AggregationBucket;
import com.hezhangjian.ontology.service.ExplorerModels.FacetBucket;
import com.hezhangjian.ontology.service.ExplorerModels.PropertyDefinition;
import com.hezhangjian.ontology.service.ExplorerPolicy.ValidatedQuery;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public final class ExplorerStorageClient {
    private final URI graphBase;
    private final URI searchBase;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, String> labelIds = new ConcurrentHashMap<>();
    private static final DateTimeFormatter HUGEGRAPH_DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Set<String> OBJECT_SYSTEM_PROPERTIES = Set.of(
            "object_key", "ontology_id", "object_type", "object_id", "projection_sequence",
            "correlation_id", "occurred_at");

    public ExplorerStorageClient(
            HugeGraphProperties hugeGraph,
            OpenSearchProperties openSearch,
            ObjectMapper objectMapper) {
        this.graphBase = hugeGraph.url().resolve("/graphspaces/DEFAULT/graphs/hugegraph/");
        this.searchBase = trailingSlash(openSearch.url());
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public SearchPage search(
            ValidatedQuery query, Actor actor, List<Object> searchAfter) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", query.pageSize());
        body.put("track_total_hits", 10000);
        ObjectNode bool = objectMapper.createObjectNode();
        ArrayNode filters = objectMapper.createArrayNode();
        filters.add(term("ontology_id", WorkspaceContext.id().toString()));
        filters.add(term("object_type", query.type().physicalKey()));
        filters.add(visibility(actor));
        JsonNode where = compileWhere(query.request().where(), query.properties());
        if (where != null) {
            filters.add(where);
        }
        bool.set("filter", filters);
        body.set("query", objectMapper.createObjectNode().set("bool", bool));
        ArrayNode sort = body.putArray("sort");
        if (query.request().sort() == null || query.request().sort().isEmpty()) {
            sort.add(sort("occurred_at", "desc"));
        } else {
            query.request().sort().forEach(clause -> {
                PropertyDefinition property = query.properties().get(clause.propertyId());
                sort.add(sort(field(property), clause.direction()));
            });
        }
        sort.add(sort("object_id", "asc"));
        if (searchAfter != null && !searchAfter.isEmpty()) {
            body.set("search_after", objectMapper.valueToTree(searchAfter));
        }
        JsonNode response = json("POST", searchBase.resolve("ontology-object-*/_search"), body,
                "搜索服务暂时不可用，请稍后重试");
        List<SearchHit> hits = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            hits.add(new SearchHit(source.path("object_id").asText(), source.path("projection_sequence").asLong(),
                    source.path("properties"),
                    parseInstant(source.path("occurred_at").asText()),
                    objectMapper.convertValue(hit.path("sort"), List.class)));
        }
        JsonNode total = response.path("hits").path("total");
        return new SearchPage(List.copyOf(hits), total.path("value").asLong(),
                "gte".equals(total.path("relation").asText()), Instant.now());
    }

    public List<RawSearchHit> globalSearch(String text, int size, Actor actor) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", size);
        body.put("track_total_hits", 10000);
        ObjectNode bool = objectMapper.createObjectNode();
        bool.set("filter", objectMapper.createArrayNode()
                .add(term("ontology_id", WorkspaceContext.id().toString())).add(visibility(actor)));
        if (text == null || text.isBlank()) {
            bool.set("must", objectMapper.createArrayNode().add(objectMapper.createObjectNode().set("match_all", objectMapper.createObjectNode())));
        } else {
            ObjectNode multi = objectMapper.createObjectNode();
            multi.put("query", sanitizeSearch(text));
            multi.set("fields", objectMapper.createArrayNode().add("object_id^3").add("properties.*"));
            multi.put("operator", "and");
            bool.set("must", objectMapper.createArrayNode().add(objectMapper.createObjectNode().set("multi_match", multi)));
        }
        body.set("query", objectMapper.createObjectNode().set("bool", bool));
        body.set("sort", objectMapper.createArrayNode().add("_score").add(sort("object_id", "asc")));
        JsonNode response = json("POST", searchBase.resolve("ontology-object-*/_search"), body,
                "搜索服务暂时不可用，请稍后重试");
        List<RawSearchHit> result = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            result.add(new RawSearchHit(source.path("object_type").asText(), source.path("object_id").asText(),
                    source.path("projection_sequence").asLong(),
                    source.path("properties"), parseInstant(source.path("occurred_at").asText())));
        }
        return List.copyOf(result);
    }

    public Map<UUID, List<FacetBucket>> facets(
            ValidatedQuery query, List<UUID> propertyIds, Actor actor) {
        if (propertyIds == null || propertyIds.size() > 10) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "单次最多查询 10 个 Facet");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", 0);
        ArrayNode filters = objectMapper.createArrayNode()
                .add(term("ontology_id", WorkspaceContext.id().toString()))
                .add(term("object_type", query.type().physicalKey())).add(visibility(actor));
        JsonNode where = compileWhere(query.request().where(), query.properties());
        if (where != null) filters.add(where);
        body.set("query", objectMapper.createObjectNode().set("bool", objectMapper.createObjectNode().set("filter", filters)));
        ObjectNode aggs = body.putObject("aggs");
        for (UUID id : propertyIds) {
            PropertyDefinition property = query.properties().get(id);
            if (property == null || !property.filterable() || property.sensitive()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Facet 字段不存在、不可筛选或无权访问");
            }
            aggs.putObject(id.toString()).set("terms", objectMapper.createObjectNode().put("field", field(property)).put("size", 100));
        }
        JsonNode response = json("POST", searchBase.resolve("ontology-object-*/_search"), body,
                "搜索服务暂时不可用，Facet 未执行");
        Map<UUID, List<FacetBucket>> result = new LinkedHashMap<>();
        for (UUID id : propertyIds) {
            List<FacetBucket> buckets = new ArrayList<>();
            for (JsonNode bucket : response.path("aggregations").path(id.toString()).path("buckets")) {
                buckets.add(new FacetBucket(jsonValue(bucket.path("key")), bucket.path("doc_count").asLong()));
            }
            result.put(id, List.copyOf(buckets));
        }
        return result;
    }

    public List<AggregationBucket> aggregate(ValidatedQuery query, UUID dimensionPropertyId,
                                      UUID measurePropertyId, String aggregation, Actor actor) {
        return aggregate(query, List.of(dimensionPropertyId), measurePropertyId, null, aggregation, actor);
    }

    public List<AggregationBucket> aggregate(ValidatedQuery query, List<UUID> dimensionPropertyIds,
                                      UUID measurePropertyId, UUID divisorPropertyId,
                                      String aggregation, Actor actor) {
        if (dimensionPropertyIds == null || dimensionPropertyIds.isEmpty() || dimensionPropertyIds.size() > 3) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "图表必须选择 1—3 个分组字段");
        }
        List<PropertyDefinition> dimensions = dimensionPropertyIds.stream().map(id -> {
            PropertyDefinition dimension = query.properties().get(id);
            if (dimension == null || !dimension.filterable() || dimension.sensitive()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "分组字段不存在、不可筛选或无权访问");
            }
            return dimension;
        }).toList();
        PropertyDefinition measure = measurePropertyId == null ? null : query.properties().get(measurePropertyId);
        if (!List.of("count", "approx_distinct").contains(aggregation) && (measure == null || measure.sensitive()
                || !List.of("INTEGER", "LONG", "DECIMAL").contains(measure.valueType()))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "度量字段必须是可见数值属性");
        }
        PropertyDefinition divisor = divisorPropertyId == null ? null : query.properties().get(divisorPropertyId);
        if ("sum_per_distinct".equals(aggregation) && (divisor == null || divisor.sensitive())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "去重计数字段不存在或无权访问");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", 0);
        ArrayNode filters = objectMapper.createArrayNode()
                .add(term("ontology_id", WorkspaceContext.id().toString()))
                .add(term("object_type", query.type().physicalKey())).add(visibility(actor));
        JsonNode where = compileWhere(query.request().where(), query.properties());
        if (where != null) filters.add(where);
        body.set("query", objectMapper.createObjectNode().set("bool", objectMapper.createObjectNode().set("filter", filters)));
        ObjectNode grouped = body.putObject("aggs").putObject("grouped");
        ArrayNode sources = grouped.putObject("composite").put("size", 1000).putArray("sources");
        for (int index = 0; index < dimensions.size(); index++) {
            sources.add(objectMapper.createObjectNode().set(
                    "dimension_" + index,
                    objectMapper.createObjectNode().set(
                            "terms",
                            objectMapper.createObjectNode().put("field", field(dimensions.get(index))))));
        }
        if (!"count".equals(aggregation)) {
            ObjectNode metrics = grouped.putObject("aggs");
            if ("sum_per_distinct".equals(aggregation)) {
                metrics.putObject("numerator").set(
                        "sum", objectMapper.createObjectNode().put("field", field(measure)));
                metrics.putObject("divisor").set(
                        "cardinality", objectMapper.createObjectNode().put("field", field(divisor)));
            } else if ("approx_distinct".equals(aggregation)) {
                metrics.putObject("metric").set(
                        "cardinality", objectMapper.createObjectNode().put("field", field(measure)));
            } else {
                metrics.putObject("metric").set(
                        aggregation, objectMapper.createObjectNode().put("field", field(measure)));
            }
        }
        JsonNode response = json("POST", searchBase.resolve("ontology-object-*/_search"), body,
                "搜索服务暂时不可用，分组度量未执行");
        List<AggregationBucket> buckets = new ArrayList<>();
        for (JsonNode bucket : response.path("aggregations").path("grouped").path("buckets")) {
            double metric;
            if ("count".equals(aggregation)) {
                metric = bucket.path("doc_count").asDouble();
            } else if ("sum_per_distinct".equals(aggregation)) {
                double divisorValue = bucket.path("divisor").path("value").asDouble();
                metric = divisorValue == 0 ? 0 : bucket.path("numerator").path("value").asDouble() / divisorValue;
            } else {
                metric = bucket.path("metric").path("value").asDouble();
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int index = 0; index < dimensions.size(); index++) {
                values.put(
                        dimensions.get(index).displayName(),
                        jsonValue(bucket.path("key").path("dimension_" + index)));
            }
            buckets.add(new AggregationBucket(values, bucket.path("doc_count").asLong(), metric));
        }
        return List.copyOf(buckets);
    }

    public double metric(
            ValidatedQuery query, UUID measurePropertyId, String aggregation, Actor actor) {
        PropertyDefinition measure = measurePropertyId == null ? null : query.properties().get(measurePropertyId);
        if (!"count".equals(aggregation) && (measure == null || measure.sensitive())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "度量字段不存在或无权访问");
        }
        if (!List.of("approx_distinct", "avg", "count", "max", "min", "sum").contains(aggregation)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "聚合函数不受支持");
        }
        if (!List.of("count", "approx_distinct").contains(aggregation)
                && !List.of("INTEGER", "LONG", "DECIMAL").contains(measure.valueType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "度量字段必须是数值属性");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", 0);
        ArrayNode filters = objectMapper.createArrayNode()
                .add(term("ontology_id", WorkspaceContext.id().toString()))
                .add(term("object_type", query.type().physicalKey())).add(visibility(actor));
        JsonNode where = compileWhere(query.request().where(), query.properties());
        if (where != null) filters.add(where);
        body.set("query", objectMapper.createObjectNode().set(
                "bool", objectMapper.createObjectNode().set("filter", filters)));
        if (!"count".equals(aggregation)) {
            String engineAggregation = "approx_distinct".equals(aggregation) ? "cardinality" : aggregation;
            body.putObject("aggs").putObject("metric").set(
                    engineAggregation, objectMapper.createObjectNode().put("field", field(measure)));
        }
        JsonNode response = json("POST", searchBase.resolve("ontology-object-*/_search"), body,
                "搜索服务暂时不可用，度量未执行");
        return "count".equals(aggregation)
                ? response.path("hits").path("total").path("value").asDouble()
                : response.path("aggregations").path("metric").path("value").asDouble();
    }

    public GraphObject getObject(String objectType, String objectId) {
        String labelId = objectLabelId(objectType);
        String key = Base64.getUrlEncoder().withoutPadding()
                .encodeToString((WorkspaceContext.id() + ":" + objectType + "\u0000" + objectId)
                        .getBytes(StandardCharsets.UTF_8));
        String graphId = labelId + ":" + key;
        URI uri = graphBase.resolve("graph/vertices/" + encodeJson(graphId)
                + "?label=" + encode(objectType));
        HttpResult result = exchange("GET", uri, null);
        if (result.status == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "对象不存在或无权访问");
        }
        if (result.status / 100 != 2) {
            throw unavailable("对象存储暂时不可用");
        }
        JsonNode vertex = result.json;
        JsonNode properties = vertex.path("properties");
        try {
            return new GraphObject(vertex.path("id").asText(), objectType,
                    properties.path("object_id").asText(), properties.path("projection_sequence").asLong(),
                    graphPayload(properties),
                    properties.path("correlation_id").asText(), parseInstant(properties.path("occurred_at").asText()));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "对象正文格式无效");
        }
    }

    public List<GraphEdge> links(GraphObject object, int limit) {
        URI uri = graphBase.resolve("graph/edges?vertex_id=" + encodeJson(object.graphId())
                + "&direction=BOTH&limit=" + limit);
        JsonNode response = json("GET", uri, null, "关系存储暂时不可用");
        List<GraphEdge> edges = new ArrayList<>();
        for (JsonNode edge : response.path("edges")) {
            String direction = object.graphId().equals(edge.path("outV").asText()) ? "OUT" : "IN";
            JsonNode properties = edge.path("properties");
            String targetType = "OUT".equals(direction)
                    ? properties.path("target_object_type").asText()
                    : properties.path("source_object_type").asText();
            String targetId = "OUT".equals(direction)
                    ? properties.path("target_object_id").asText()
                    : properties.path("source_object_id").asText();
            GraphObject target = getObject(targetType, targetId);
            ObjectNode payload = objectMapper.createObjectNode();
            properties.properties().forEach(entry -> {
                if (!Set.of(
                        "relation_key", "ontology_id", "relation_type", "relation_id",
                        "source_object_type", "source_object_id", "target_object_type",
                        "target_object_id", "projection_sequence",
                        "correlation_id", "occurred_at").contains(entry.getKey())) {
                    payload.set(entry.getKey(), entry.getValue());
                }
            });
            edges.add(new GraphEdge(properties.path("relation_id").asText(),
                    properties.path("relation_type").asText(), direction, target, payload));
        }
        return List.copyOf(edges);
    }

    public boolean searchAvailable() {
        try {
            return exchange("HEAD", searchBase.resolve("ontology-object-*"), null).status == 200;
        } catch (Exception exception) {
            return false;
        }
    }

    private String objectLabelId(String objectType) {
        return labelIds.computeIfAbsent(objectType, type -> {
            String id = json(
                    "GET",
                    graphBase.resolve("schema/vertexlabels/" + encode(type)),
                    null,
                    "对象类型 Schema 暂时不可用").path("id").asText();
            if (id.isBlank()) throw unavailable("对象类型 Schema 无效");
            return id;
        });
    }

    private ObjectNode graphPayload(JsonNode properties) {
        ObjectNode payload = objectMapper.createObjectNode();
        properties.properties().forEach(entry -> {
            if (!OBJECT_SYSTEM_PROPERTIES.contains(entry.getKey())) {
                payload.set(entry.getKey(), entry.getValue());
            }
        });
        return payload;
    }

    private JsonNode compileWhere(Map<String, Object> node, Map<UUID, PropertyDefinition> properties) {
        if (node == null || node.isEmpty()) return null;
        String type = String.valueOf(node.get("type"));
        if (List.of("and", "or", "not").contains(type)) {
            ArrayNode compiled = objectMapper.createArrayNode();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
            children.forEach(child -> compiled.add(compileWhere(child, properties)));
            ObjectNode bool = objectMapper.createObjectNode();
            if ("and".equals(type)) bool.set("filter", compiled);
            if ("or".equals(type)) { bool.set("should", compiled); bool.put("minimum_should_match", 1); }
            if ("not".equals(type)) bool.set("must_not", compiled);
            return objectMapper.createObjectNode().set("bool", bool);
        }
        if ("relation".equals(type)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "关系属性筛选需要已发布关系投影；请使用沿关系探索");
        }
        UUID id = UUID.fromString(String.valueOf(node.get("propertyId")));
        PropertyDefinition property = properties.get(id);
        String field = field(property);
        String operator = String.valueOf(node.get("operator"));
        Object value = node.get("value");
        return switch (operator) {
            case "eq", "exact" -> term(field, value);
            case "ne" -> objectMapper.createObjectNode().set("bool", objectMapper.createObjectNode()
                    .set("must_not", objectMapper.createArrayNode().add(term(field, value))));
            case "in" -> objectMapper.createObjectNode().set("terms", objectMapper.createObjectNode().set(field, objectMapper.valueToTree(value)));
            case "not_in" -> objectMapper.createObjectNode().set("bool", objectMapper.createObjectNode().set("must_not",
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode().set("terms", objectMapper.createObjectNode().set(field, objectMapper.valueToTree(value))))));
            case "gt", "gte", "lt", "lte" -> objectMapper.createObjectNode().set("range", objectMapper.createObjectNode()
                    .set(field, objectMapper.createObjectNode().set(operator, objectMapper.valueToTree(value))));
            case "between" -> {
                List<?> values = (List<?>) value;
                ObjectNode bounds = objectMapper.createObjectNode();
                bounds.set("gte", objectMapper.valueToTree(values.get(0)));
                bounds.set("lte", objectMapper.valueToTree(values.get(1)));
                yield objectMapper.createObjectNode().set("range", objectMapper.createObjectNode().set(field, bounds));
            }
            case "starts_with" -> objectMapper.createObjectNode().set("prefix", objectMapper.createObjectNode().put(field, String.valueOf(value)));
            case "contains_all_words" -> match("and", property.physicalKey(), value);
            case "contains_any_word" -> match("or", property.physicalKey(), value);
            case "is_empty" -> objectMapper.createObjectNode().set("bool", objectMapper.createObjectNode().set("must_not",
                    objectMapper.createArrayNode().add(objectMapper.createObjectNode().set("exists", objectMapper.createObjectNode().put("field", "properties." + property.physicalKey())))));
            case "is_not_empty" -> objectMapper.createObjectNode().set("exists", objectMapper.createObjectNode().put("field", "properties." + property.physicalKey()));
            case "recent_days" -> objectMapper.createObjectNode().set("range", objectMapper.createObjectNode().set(field,
                    objectMapper.createObjectNode().put("gte", "now-" + value + "d/d")));
            case "contains_any", "contains_all" -> objectMapper.createObjectNode().set("terms", objectMapper.createObjectNode().set(field, objectMapper.valueToTree(value)));
            default -> throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持的筛选操作符");
        };
    }

    private ObjectNode visibility(Actor actor) {
        ArrayNode values = objectMapper.createArrayNode().add("authenticated");
        actor.roles().forEach(role -> values.add("role:" + role));
        values.add("user:" + actor.id());
        return objectMapper.createObjectNode().set("terms", objectMapper.createObjectNode().set("visibility_tokens", values));
    }

    private ObjectNode term(String field, Object value) {
        return objectMapper.createObjectNode().set("term", objectMapper.createObjectNode().set(field, objectMapper.valueToTree(value)));
    }

    private ObjectNode match(String operator, String apiName, Object value) {
        return objectMapper.createObjectNode().set("match", objectMapper.createObjectNode().set("properties." + apiName,
                objectMapper.createObjectNode().put("query", String.valueOf(value)).put("operator", operator)));
    }

    private ObjectNode sort(String field, String direction) {
        return objectMapper.createObjectNode().set(field, objectMapper.createObjectNode().put("order", direction).put("unmapped_type", "keyword"));
    }

    private String field(PropertyDefinition property) {
        String base = "properties." + property.physicalKey();
        return List.of("STRING", "ENUM", "STRING_ARRAY").contains(property.valueType()) ? base + ".keyword" : base;
    }

    private Object jsonValue(JsonNode value) {
        if (value.isNumber()) return value.numberValue();
        if (value.isBoolean()) return value.booleanValue();
        return value.asText();
    }

    private String sanitizeSearch(String value) {
        if (value.contains("*")) {
            int index = value.indexOf('*');
            if (index == 0 || index < value.length() - 1) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "只允许词尾通配符 term*");
            }
        }
        if (value.contains("/") || value.contains("~")) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "不支持 Regex 或模糊查询语法");
        }
        return value.replace("*", "");
    }

    private JsonNode json(String method, URI uri, JsonNode body, String safeMessage) {
        HttpResult result = exchange(method, uri, body);
        if (result.status / 100 != 2) {
            throw unavailable(safeMessage);
        }
        return result.json;
    }

    private HttpResult exchange(String method, URI uri, JsonNode body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").header("Accept-Encoding", "gzip");
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            HttpResponse<byte[]> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] bytes = response.body();
            if ("gzip".equalsIgnoreCase(response.headers().firstValue("Content-Encoding").orElse(""))) {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                    bytes = gzip.readAllBytes();
                }
            }
            JsonNode json = bytes.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(bytes);
            return new HttpResult(response.statusCode(), json);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw unavailable("对象存储暂时不可用");
        }
    }

    private String encodeJson(String value) {
        try {
            return URLEncoder.encode(objectMapper.writeValueAsString(value), StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception notIsoInstant) {
            try {
                return LocalDateTime.parse(value, HUGEGRAPH_DATE).toInstant(ZoneOffset.UTC);
            } catch (Exception invalidDate) {
                return Instant.EPOCH;
            }
        }
    }

    private URI trailingSlash(URI uri) {
        return URI.create(uri.toString().endsWith("/") ? uri.toString() : uri + "/");
    }

    private ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message);
    }

    public record SearchHit(
            String objectId, long projectionSequence, JsonNode properties,
                     Instant updatedAt, List<Object> sort) { }
    public record RawSearchHit(
            String objectType, String objectId, long projectionSequence,
                        JsonNode properties, Instant updatedAt) { }
    public record SearchPage(
            List<SearchHit> hits, long total, boolean lowerBound, Instant indexUpdatedAt) { }
    public record GraphObject(String graphId, String objectType, String objectId, long projectionSequence,
                              JsonNode payload, String correlationId, Instant updatedAt) { }
    public record GraphEdge(
            String relationId, String relationType, String direction,
                     GraphObject target, JsonNode properties) { }
    private record HttpResult(int status, JsonNode json) { }
}
