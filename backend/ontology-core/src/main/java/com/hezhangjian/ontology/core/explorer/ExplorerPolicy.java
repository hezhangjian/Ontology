package com.hezhangjian.ontology.core.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.ObjectSetRequest;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.ObjectTypeDefinition;
import com.hezhangjian.ontology.core.explorer.ExplorerModels.PropertyDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class ExplorerPolicy {
    private static final Set<String> LOGICAL = Set.of("and", "or", "not");
    private static final Set<String> OPERATORS = Set.of(
            "eq", "ne", "gt", "gte", "lt", "lte", "between", "in", "not_in",
            "contains_all_words", "contains_any_word", "exact", "starts_with",
            "is_empty", "is_not_empty", "contains_any", "contains_all", "recent_days");
    private final ObjectMapper objectMapper;

    ExplorerPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    ValidatedQuery validate(ObjectSetRequest request, ObjectTypeDefinition type) {
        if (request == null || request.objectTypeId() == null || !request.objectTypeId().equals(type.id())) {
            throw invalid("对象类型无效");
        }
        int pageSize = request.pageSize() == null ? 50 : request.pageSize();
        if (pageSize != 25 && pageSize != 50 && pageSize != 100) {
            throw invalid("每页数量只能是 25、50 或 100");
        }
        Map<UUID, PropertyDefinition> properties = new HashMap<>();
        type.properties().forEach(property -> properties.put(property.id(), property));
        Counter counter = new Counter();
        validateNode(request.where(), 1, properties, counter);
        if (counter.leaves > 50) {
            throw invalid("筛选条件最多 50 个，请缩小查询");
        }
        if (counter.relations > 3) {
            throw invalid("关系条件最多 3 个且仅支持一跳");
        }
        if (request.sort() != null && request.sort().size() > 5) {
            throw invalid("排序字段最多 5 个");
        }
        if (request.sort() != null) {
            request.sort().forEach(sort -> {
                PropertyDefinition property = properties.get(sort.propertyId());
                if (property == null || !property.sortable() || property.sensitive()) {
                    throw invalid("排序字段不存在、不可排序或无权访问");
                }
                if (!List.of("asc", "desc").contains(sort.direction())) {
                    throw invalid("排序方向只能是 asc 或 desc");
                }
            });
        }
        if (request.columns() != null) {
            request.columns().forEach(id -> {
                PropertyDefinition property = properties.get(id);
                if (property == null || property.sensitive()) {
                    throw invalid("列字段不存在或无权访问");
                }
            });
        }
        Map<String, Object> queryIdentity = Map.of(
                "objectTypeId", request.objectTypeId(),
                "where", request.where() == null ? Map.of() : request.where(),
                "sort", request.sort() == null ? List.of() : request.sort(),
                "pageSize", pageSize,
                "columns", request.columns() == null ? List.of() : request.columns());
        return new ValidatedQuery(request, type, properties, pageSize, fingerprint(queryIdentity));
    }

    private void validateNode(Map<String, Object> node, int depth,
                              Map<UUID, PropertyDefinition> properties, Counter counter) {
        if (node == null || node.isEmpty()) {
            return;
        }
        if (depth > 3) {
            throw invalid("逻辑筛选最多嵌套 3 层");
        }
        String type = String.valueOf(node.get("type"));
        if (LOGICAL.contains(type)) {
            Object rawChildren = node.get("children");
            if (!(rawChildren instanceof List<?> children) || children.isEmpty()) {
                throw invalid("逻辑筛选必须包含子条件");
            }
            if ("not".equals(type) && children.size() != 1) {
                throw invalid("NOT 只能包含一个子条件");
            }
            for (Object child : children) {
                if (!(child instanceof Map<?, ?> childMap)) {
                    throw invalid("筛选条件格式无效");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) childMap;
                validateNode(typed, depth + 1, properties, counter);
            }
            return;
        }
        if ("relation".equals(type)) {
            counter.leaves++;
            counter.relations++;
            if (Boolean.TRUE.equals(node.get("multiHop"))) {
                throw invalid("关系筛选只允许一跳");
            }
            return;
        }
        if (!"property".equals(type)) {
            throw invalid("未知筛选类型");
        }
        counter.leaves++;
        UUID propertyId;
        try {
            propertyId = UUID.fromString(String.valueOf(node.get("propertyId")));
        } catch (Exception exception) {
            throw invalid("属性筛选必须使用稳定属性 ID");
        }
        PropertyDefinition property = properties.get(propertyId);
        if (property == null || !property.filterable() || property.sensitive()) {
            throw invalid("属性不存在、不可筛选或无权访问");
        }
        String operator = String.valueOf(node.get("operator"));
        if (!OPERATORS.contains(operator)) {
            throw invalid("不支持的属性操作符");
        }
        if (("between".equals(operator) || "in".equals(operator) || "not_in".equals(operator)
                || "contains_any".equals(operator) || "contains_all".equals(operator))
                && !(node.get("value") instanceof List<?>)) {
            throw invalid("该操作符需要数组值");
        }
    }

    String fingerprint(Object value) {
        try {
            byte[] canonical = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot fingerprint explorer query", exception);
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    record ValidatedQuery(ObjectSetRequest request, ObjectTypeDefinition type,
                          Map<UUID, PropertyDefinition> properties, int pageSize,
                          String fingerprint) { }

    private static final class Counter {
        private int leaves;
        private int relations;
    }
}
