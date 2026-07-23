package com.hezhangjian.ontology.core.modeling;

import static com.hezhangjian.ontology.core.modeling.ModelingModels.PropertyDraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionProblem;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ModelingPolicy {
    private static final List<String> VALUE_TYPES = List.of("STRING", "INTEGER", "LONG", "DECIMAL", "BOOLEAN", "DATE", "DATETIME", "ENUM", "STRING_ARRAY", "INTEGER_ARRAY", "JSON");
    private final ObjectMapper json;

    public ModelingPolicy(ObjectMapper json) {
        this.json = json;
    }

    public void validateProperties(List<PropertyDraft> properties) {
        if (properties == null || properties.isEmpty()) throw problem("PROPERTIES_REQUIRED", "对象类型至少需要一个属性");
        if (properties.stream().filter(PropertyDraft::primaryKey).count() != 1) throw problem("PRIMARY_KEY_REQUIRED", "对象类型必须恰好有一个主键");
        if (properties.stream().filter(PropertyDraft::titleProperty).count() > 1) throw problem("TITLE_PROPERTY_INVALID", "对象类型最多有一个标题属性");
        HashSet<String> names = new HashSet<>();
        for (PropertyDraft property : properties) {
            if (property.apiName() == null || !property.apiName().matches("[A-Za-z][A-Za-z0-9_]{0,159}")) throw problem("API_NAME_INVALID", "属性 API 名称无效");
            if (!names.add(property.apiName().toLowerCase(Locale.ROOT))) throw problem("PROPERTY_NAME_CONFLICT", "属性 API 名称重复");
            String type = upper(property.valueType());
            if (!VALUE_TYPES.contains(type)) throw problem("PROPERTY_TYPE_INVALID", property.apiName() + " 的属性类型无效");
            if (property.primaryKey() && (!property.required() || !List.of("STRING", "INTEGER", "LONG").contains(type))) throw problem("PRIMARY_KEY_TYPE_INVALID", "主键必须必填且类型为 string/integer/long");
            if ("JSON".equals(type) && (property.primaryKey() || property.sortable())) throw problem("JSON_PROPERTY_RESTRICTED", "JSON 属性不能作为主键或排序字段");
            if (type.endsWith("_ARRAY") && property.primaryKey()) throw problem("ARRAY_PROPERTY_RESTRICTED", "数组属性不能作为主键");
        }
    }

    public void validateActionRules(String actionOperation, List<Map<String, Object>> rules) {
        String contractOperation = upper(actionOperation);
        HashSet<String> writes = new HashSet<>();
        for (Map<String, Object> rule : rules == null ? List.<Map<String, Object>>of() : rules) {
            String operation = upper(Objects.toString(
                    rule.containsKey("operation") ? rule.get("operation") : rule.get("type"),
                    List.of("LINK", "UNLINK").contains(contractOperation) ? contractOperation : "SET_PROPERTY"));
            if ("SET_PROPERTY".equals(operation)) {
                if (!List.of("CREATE", "UPDATE").contains(contractOperation)) {
                    throw problem("ACTION_RULE_OPERATION_INVALID", contractOperation + " Action 不能写入对象属性");
                }
                String target = Objects.toString(rule.get("targetPropertyId"), "");
                if (target.isBlank()) throw problem("ACTION_PROPERTY_REQUIRED", "SET_PROPERTY 必须指定目标属性");
                if (!writes.add(target)) throw problem("ACTION_RULE_CONFLICT", "Action 规则不能对同一属性产生冲突写入");
            } else if (List.of("LINK", "UNLINK").contains(operation)) {
                if (!operation.equals(contractOperation)) {
                    throw problem("ACTION_RULE_OPERATION_INVALID", "关系规则必须与 Action 操作一致");
                }
                if (Objects.toString(rule.get("relationTypeId"), "").isBlank()) {
                    throw problem("ACTION_RELATION_REQUIRED", operation + " 必须指定关系类型");
                }
            } else {
                throw problem("ACTION_RULE_UNSUPPORTED", "不支持的 Action 规则：" + operation);
            }
        }
        if (List.of("CREATE", "UPDATE", "LINK", "UNLINK").contains(contractOperation)
                && (rules == null || rules.isEmpty())) {
            throw problem("ACTION_RULE_REQUIRED", contractOperation + " Action 至少需要一条声明式规则");
        }
    }

    public void validateActionContract(String operation, String approvalPolicy) {
        if (!List.of("CREATE", "LINK", "RETIRE", "UNLINK", "UPDATE").contains(upper(operation))) {
            throw problem("ACTION_OPERATION_UNSUPPORTED", "Action 操作类型无效");
        }
        if (!List.of("ALWAYS", "CONDITIONAL", "NONE").contains(upper(approvalPolicy))) {
            throw problem("ACTION_APPROVAL_INVALID", "Action 审批策略无效");
        }
    }

    public void validateFunctionDsl(Map<String, Object> dsl) {
        validateFunctionNode(dsl == null ? Map.of() : dsl);
    }

    private void validateFunctionNode(Object node) {
        if (node instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                String key = Objects.toString(entry.getKey(), "").toLowerCase(Locale.ROOT);
                if (List.of("mutation", "script", "gremlin", "opensearch", "sql", "webhook").contains(key)) {
                    throw problem("FUNCTION_DSL_WRITE_FORBIDDEN", "Function 只允许受限只读 DSL");
                }
                if ("tool".equals(key) && List.of("update", "delete", "create", "mutation", "execute_action")
                        .contains(Objects.toString(entry.getValue(), "").toLowerCase(Locale.ROOT))) {
                    throw problem("FUNCTION_DSL_WRITE_FORBIDDEN", "Function 只允许受限只读 DSL");
                }
                validateFunctionNode(entry.getValue());
            }
        } else if (node instanceof List<?> values) {
            values.forEach(this::validateFunctionNode);
        }
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private ConnectionProblem problem(String code, String message) { return new ConnectionProblem(code, message); }
}
