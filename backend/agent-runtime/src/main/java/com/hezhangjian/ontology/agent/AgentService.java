package com.hezhangjian.ontology.agent;

import static com.hezhangjian.ontology.agent.AgentModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
final class AgentService {
    private final ConversationStore conversations;
    private final DeepSeekClient deepSeek;
    private final int maxToolRounds;
    private final ObjectMapper json;
    private final OntologyToolClient tools;

    AgentService(ConversationStore conversations, DeepSeekClient deepSeek, ObjectMapper json,
                 OntologyToolClient tools, @Value("${agent.max-tool-rounds:8}") int maxToolRounds) {
        this.conversations = conversations;
        this.deepSeek = deepSeek;
        this.json = json;
        this.tools = tools;
        this.maxToolRounds = Math.min(Math.max(maxToolRounds, 1), 12);
    }

    void stream(UUID conversationId, String content, OntologyToolClient.RequestContext context,
                Consumer<StreamEvent> events) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("消息不能为空");
        Message userMessage = conversations.append(conversationId, context.ontologyId(), "user", content.trim(), List.of());
        events.accept(new StreamEvent("user_message", userMessage));
        Conversation conversation = conversations.get(conversationId, context.ontologyId());
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt()));
        conversation.messages().forEach(message -> messages.add(Map.of("role", message.role(), "content", message.content())));
        List<ToolTrace> traces = new ArrayList<>();
        UUID streamedMessageId = UUID.randomUUID();
        events.accept(new StreamEvent("assistant_start", Map.of("id", streamedMessageId, "createdAt", java.time.Instant.now())));
        String answer = null;
        boolean governedRecommendation = false;
        for (int round = 0; round < maxToolRounds; round++) {
            Map<String, Object> assistant = deepSeek.complete(messages,
                    governedRecommendation ? governedToolDefinitions() : toolDefinitions());
            List<Map<String, Object>> calls = json.convertValue(assistant.get("tool_calls"), new TypeReference<>() { });
            if (calls == null || calls.isEmpty()) {
                answer = String.valueOf(assistant.getOrDefault("content", "未能生成回答"));
                break;
            }
            messages.add(assistant);
            for (Map<String, Object> call : calls) {
                String callId = String.valueOf(call.get("id"));
                Map<String, Object> function = map(call.get("function"));
                String name = String.valueOf(function.get("name"));
                Map<String, Object> arguments = Map.of();
                try {
                    arguments = json.readValue(String.valueOf(function.getOrDefault("arguments", "{}")), new TypeReference<>() { });
                } catch (Exception ignored) {
                    // The failed parse is reported as the tool result below.
                }
                events.accept(new StreamEvent("tool_start", new ToolTrace(callId, name, arguments, null,
                        "design_rule_transform".equals(name) || "preview_action".equals(name))));
                Object result;
                try {
                    arguments = json.readValue(String.valueOf(function.getOrDefault("arguments", "{}")), new TypeReference<>() { });
                    if (governedRecommendation && !"preview_action".equals(name)) {
                        result = Map.of("error", "Function 已返回排他性的受治理建议，后续只允许预览其指定 Action", "recoverable", false);
                    } else {
                        result = tools.execute(name, arguments, context);
                        if ("execute_function".equals(name) && containsGovernedRecommendation(result)) {
                            governedRecommendation = true;
                        }
                    }
                } catch (Exception failure) {
                    result = Map.of("error", safeError(failure), "recoverable", true);
                }
                ToolTrace trace = new ToolTrace(callId, name, arguments, result,
                        "design_rule_transform".equals(name) || "preview_action".equals(name));
                traces.add(trace);
                events.accept(new StreamEvent("tool_result", trace));
                messages.add(Map.of("role", "tool", "tool_call_id", callId, "content", write(result)));
            }
        }
        if (answer == null) answer = "已达到本次分析的工具调用上限，请缩小问题范围后继续。";
        Message message = conversations.append(conversationId, context.ontologyId(), "assistant", answer, traces);
        int[] codePoints = answer.codePoints().toArray();
        for (int offset = 0; offset < codePoints.length; offset += 8) {
            String delta = new String(codePoints, offset, Math.min(8, codePoints.length - offset));
            events.accept(new StreamEvent("content_delta", Map.of("id", streamedMessageId, "delta", delta)));
        }
        events.accept(new StreamEvent("done", Map.of("conversation", conversations.get(conversationId, context.ontologyId()),
                "message", message)));
    }

    private String systemPrompt() {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        return """
                你是通用本体数据平台的智能助手。今天是 %s，平台业务时区是 Asia/Shanghai。
                所有业务事实必须来自工具结果；先通过 list_object_types 获取稳定对象类型和属性 ID，绝不猜测 UUID。
                查询对象使用受限 Object Set AST。需要排名、总数或平均值时使用 facet_objects 或 aggregate_objects。
                所有查询筛选优先使用 filters 数组，每项包含 propertyId、operator 和 value；operator 使用 eq、ne、gt、gte、lt、lte、between、in、not_in 或 recent_days。
                facet_objects 适合精确频次排名；不要为了自行计数而拉取整页对象明细。
                用户给出显示名称、但当前对象只有标识符属性时，先用 facet_objects 查看实际标识符，不要把显示名称直接当作 ID 筛选。
                本体中的 Function 是经过治理、版本化的确定性业务语义。先调用 list_functions；存在适用 Function 时必须使用 execute_function，不得用临时查询重新实现同一业务逻辑。
                Function 返回的 governedRecommendation 是已治理的规范性决策；只能解释并按其中指定的 Action 和参数预览，禁止根据观测值自行改写阈值或发明替代方案。
                一旦 Function 返回 governedRecommendation，它就是排他的整改结论；不得再建议额外的管道操作、告警处置、硬件检查或其他 Action，除非该建议本身明确包含这些内容。
                需要跨对象分析时先读取 list_link_types 并沿关系使用 traverse_relations；禁止仅凭同名字符串字段臆造跨对象关联。语义路径缺失时应报告本体缺口，而不是暴力拉取数据自行 join。
                Action 是唯一允许改变业务状态的入口。先读取 list_actions，再使用 preview_action；不要用数据管道变更冒充业务 Action。
                回答中明确使用的 Function 版本、关系路径、时间范围、样本数和证据来源。
                你不能生成或执行 SQL、Gremlin、OpenSearch DSL，也不能声称没有数据支持的修复效果。
                写操作必须先调用 preview_action 或 design_rule_transform，向用户解释 diff，并明确要求在界面点击确认；不要声称预览已经执行。
                规则清洗必须保留原始值审计字段。保持回答简洁、使用中文。
                """.formatted(today);
    }

    private List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool("aggregate_objects", "对一个对象集合按 1 到 3 个属性分组并执行 count/avg/sum/min/max 等聚合。", schema(
                        required("objectTypeId", "dimensionPropertyIds", "aggregation"), properties(
                                entry("aggregation", stringEnum("count", "avg", "sum", "min", "max", "approx_distinct", "sum_per_distinct")),
                                entry("dimensionPropertyIds", array("string")), entry("filters", filters()),
                                entry("measurePropertyId", string()), entry("objectTypeId", string())))),
                tool("design_rule_transform", "生成一个通用规则清洗节点变更预览，不会自动修改管道。", schema(
                        required("pipelineId", "beforeNodeId", "field", "min", "max", "replacement"), properties(
                                entry("beforeNodeId", string()), entry("field", string()), entry("max", number()), entry("min", number()),
                                entry("pipelineId", string()), entry("preserveOriginalAs", string()), entry("replacement", number()), entry("statusField", string())))),
                tool("execute_function", "执行已发布 Function 中受治理、只读、确定性的语义步骤；不会让模型临时拼接分析逻辑。", schema(
                        required("functionId"), properties(entry("functionId", string()), entry("inputs", object())))),
                tool("facet_objects", "统计对象集合中一个或多个属性值的精确出现次数。", schema(
                        required("objectTypeId", "propertyIds"), properties(entry("filters", filters()), entry("objectTypeId", string()), entry("propertyIds", array("string"))))),
                tool("get_object", "读取一个对象的当前属性、版本和对象类型。", schema(required("objectTypeId", "objectId"), properties(entry("objectId", string()), entry("objectTypeId", string())))),
                tool("get_pipeline", "读取管道草稿、节点、连线和 ETag。", schema(required("pipelineId"), properties(entry("pipelineId", string())))),
                tool("list_actions", "列出当前本体中已定义的 Action。", schema(List.of(), Map.of())),
                tool("list_functions", "列出当前本体中已发布、版本化的只读 Function。跨对象业务分析应优先选择适用 Function。", schema(List.of(), Map.of())),
                tool("list_link_types", "列出当前本体的关系类型。", schema(List.of(), Map.of())),
                tool("list_object_types", "列出当前本体的对象类型及稳定属性 ID。分析开始时优先调用。", schema(List.of(), Map.of())),
                tool("list_pipelines", "列出当前工作空间的数据管道。", schema(List.of(), Map.of())),
                tool("preview_action", "预览已发布 Action 对一个对象产生的属性 diff，不执行写入。", schema(
                        required("actionId", "objectId"), properties(entry("actionId", string()), entry("objectId", string()), entry("objectVersion", number()), entry("parameters", object())))),
                tool("query_objects", "按稳定属性 ID 构造受限 AST 查询对象。", schema(
                        required("objectTypeId"), properties(entry("columns", array("string")), entry("filters", filters()), entry("objectTypeId", string()), entry("sort", array("object"))))),
                tool("traverse_relations", "从一个对象沿已发布关系向两端展开一跳。", schema(
                        required("objectTypeId", "objectId"), properties(entry("linkTypeIds", array("string")), entry("objectId", string()), entry("objectTypeId", string()))))
        );
    }

    private List<Map<String, Object>> governedToolDefinitions() {
        return toolDefinitions().stream()
                .filter(tool -> "preview_action".equals(map(tool.get("function")).get("name")))
                .toList();
    }

    private boolean containsGovernedRecommendation(Object value) {
        if (value instanceof Map<?, ?> values) {
            if (values.containsKey("governedRecommendation")) return true;
            return values.values().stream().anyMatch(this::containsGovernedRecommendation);
        }
        if (value instanceof List<?> values) return values.stream().anyMatch(this::containsGovernedRecommendation);
        return false;
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> parameters) {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description, "parameters", parameters));
    }
    private Map<String, Object> schema(List<String> required, Map<String, Object> properties) { return Map.of("type", "object", "additionalProperties", false, "properties", properties, "required", required); }
    private List<String> required(String... values) { return List.of(values); }
    @SafeVarargs private final Map<String, Object> properties(Map.Entry<String, Object>... values) { Map<String, Object> result = new LinkedHashMap<>(); for (Map.Entry<String, Object> value : values) result.put(value.getKey(), value.getValue()); return result; }
    private Map.Entry<String, Object> entry(String key, Object value) { return Map.entry(key, value); }
    private Map<String, Object> string() { return Map.of("type", "string"); }
    private Map<String, Object> number() { return Map.of("type", "number"); }
    private Map<String, Object> object() { return Map.of("type", "object", "additionalProperties", true); }
    private Map<String, Object> array(String type) { return Map.of("type", "array", "items", Map.of("type", type)); }
    private Map<String, Object> filters() { return Map.of("type", "array", "items", Map.of(
            "type", "object", "additionalProperties", false,
            "properties", Map.of("operator", stringEnum("eq", "ne", "gt", "gte", "lt", "lte", "between", "in", "not_in", "recent_days"),
                    "propertyId", string(), "value", Map.of()),
            "required", List.of("propertyId", "operator", "value"))); }
    private Map<String, Object> stringEnum(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception failure) { return "{\"error\":\"tool result serialization failed\"}"; } }
    private String safeError(Exception failure) { String value = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); return value.substring(0, Math.min(500, value.length())); }
}
