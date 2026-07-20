package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class PipelineGraphValidator {
    private static final Set<String> OUTPUT_TYPES = Set.of("ONTOLOGY_OBJECT", "ONTOLOGY_RELATION");
    private static final Set<String> SOURCE_TYPES = Set.of("SOURCE");
    private final ObjectMapper json;

    public PipelineGraphValidator(ObjectMapper json) {
        this.json = json;
    }

    public List<NodeType> nodeTypes() {
        return List.of(
                type("AGGREGATE", "聚合", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "分组与类型化指标"),
                type("CAST", "类型转换", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "受控字段类型转换"),
                type("DEDUPLICATE", "去重", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "按键保留首条或末条记录"),
                type("DERIVE", "派生字段", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "安全表达式生成字段"),
                type("FILTER", "过滤", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "类型化 AND/OR 条件"),
                type("JOIN", "JOIN", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 2, 4, false, false, "批处理 JOIN 或流式有界维表 JOIN"),
                type("ONTOLOGY_OBJECT", "本体对象输出", "输出", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, true, "映射对象 ID 与属性"),
                type("ONTOLOGY_RELATION", "本体关系输出", "输出", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, true, "映射关系端点与属性"),
                type("QUALITY", "质量门禁", "治理", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "执行版本化质量规则"),
                type("SELECT", "选择/重命名", "转换", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 1, 1, false, false, "选择、重命名和排序字段"),
                type("SOURCE", "数据源", "输入", List.of(PipelineMode.BATCH, PipelineMode.STREAMING), 0, 0, true, false, "受控连接与资产源"),
                type("WINDOW", "窗口", "转换", List.of(PipelineMode.STREAMING), 1, 1, false, false, "滚动、滑动或会话窗口")
        );
    }

    public ValidationResult validate(PipelineGraph graph, PipelineMode mode, List<FieldSchema> sourceSchema) {
        List<ValidationIssue> issues = new ArrayList<>();
        PipelineGraph safeGraph = graph == null ? new PipelineGraph(List.of(), List.of()) : graph;
        List<PipelineNode> nodes = safeGraph.nodes() == null ? List.of() : safeGraph.nodes();
        List<PipelineEdge> edges = safeGraph.edges() == null ? List.of() : safeGraph.edges();
        Map<String, PipelineNode> byId = nodes.stream().filter(Objects::nonNull).filter(n -> n.id() != null)
                .collect(Collectors.toMap(PipelineNode::id, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        if (byId.size() != nodes.size()) issue(issues, "graph.unique_nodes", "GRAPH", "ERROR", null,
                "节点 ID 必须唯一", "检测到空或重复节点 ID。", "删除重复节点后重新校验");

        Map<String, NodeType> supported = nodeTypes().stream().collect(Collectors.toMap(NodeType::type, Function.identity()));
        for (PipelineNode node : byId.values()) {
            NodeType definition = supported.get(node.type());
            if (definition == null) {
                issue(issues, "node.unsupported." + node.id(), "GRAPH", "ERROR", node.id(),
                        "不支持的节点类型", String.valueOf(node.type()), "从节点库重新添加受支持节点");
            } else if (!definition.modes().contains(mode)) {
                issue(issues, "node.mode." + node.id(), "SEMANTICS", "ERROR", node.id(),
                        "节点不支持当前模式", definition.label() + "不能用于" + mode, "更换节点或管道模式");
            }
        }

        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (PipelineEdge edge : edges) {
            if (edge == null || edge.id() == null || !edgeIds.add(edge.id())) {
                issue(issues, "edge.unique", "GRAPH", "ERROR", null, "连线 ID 必须唯一", "检测到空或重复连线 ID。", "删除重复连线");
                continue;
            }
            if (!byId.containsKey(edge.source()) || !byId.containsKey(edge.target())) {
                issue(issues, "edge.missing_node." + edge.id(), "GRAPH", "ERROR", null,
                        "连线引用不存在的节点", edge.source() + " → " + edge.target(), "删除失效连线");
                continue;
            }
            if (edge.source().equals(edge.target())) {
                issue(issues, "edge.self." + edge.id(), "GRAPH", "ERROR", edge.source(), "节点不能连接自身", edge.id(), "删除自环连线");
            }
            outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target());
            incoming.computeIfAbsent(edge.target(), ignored -> new ArrayList<>()).add(edge.source());
        }

        long sourceCount = byId.values().stream().filter(n -> SOURCE_TYPES.contains(n.type())).count();
        long outputCount = byId.values().stream().filter(n -> OUTPUT_TYPES.contains(n.type())).count();
        if (sourceCount == 0) issue(issues, "graph.source", "GRAPH", "ERROR", null, "至少需要一个数据源", "画布没有源节点。", "从节点库添加数据源");
        if (outputCount == 0) issue(issues, "graph.output", "OUTPUT", "ERROR", null, "至少需要一个本体输出", "画布没有对象或关系输出。", "添加本体对象或关系输出");

        for (PipelineNode node : byId.values()) {
            NodeType definition = supported.get(node.type());
            if (definition == null) continue;
            int inputs = incoming.getOrDefault(node.id(), List.of()).size();
            int outputs = outgoing.getOrDefault(node.id(), List.of()).size();
            if (inputs < definition.minInputs() || inputs > definition.maxInputs()) {
                issue(issues, "node.inputs." + node.id(), "GRAPH", "ERROR", node.id(), "节点输入数量无效",
                        "当前 " + inputs + "，要求 " + definition.minInputs() + "—" + definition.maxInputs(), "调整节点连线");
            }
            if (definition.source() && inputs > 0) issue(issues, "source.incoming." + node.id(), "GRAPH", "ERROR", node.id(), "数据源只能作为起点", "源节点存在输入连线。", "删除源节点的输入连线");
            if (definition.output() && outputs > 0) issue(issues, "output.outgoing." + node.id(), "GRAPH", "ERROR", node.id(), "输出只能作为终点", "输出节点存在下游连线。", "删除输出节点的下游连线");
        }

        List<String> topological = topological(byId.keySet(), incoming, outgoing);
        if (topological.size() != byId.size()) issue(issues, "graph.cycle", "GRAPH", "ERROR", null,
                "管道必须是 DAG", "检测到环路。", "删除形成环路的连线");

        Map<String, List<FieldSchema>> schemas = new HashMap<>();
        List<PipelineNode> normalizedNodes = new ArrayList<>();
        for (String id : topological) {
            PipelineNode node = byId.get(id);
            List<String> predecessors = incoming.getOrDefault(id, List.of());
            List<FieldSchema> input = predecessors.stream().flatMap(parent -> schemas.getOrDefault(parent, List.of()).stream()).toList();
            List<FieldSchema> output = propagate(node, input, sourceSchema, mode, incoming, byId, issues);
            schemas.put(id, output);
            List<String> invalid = issues.stream().filter(i -> Objects.equals(i.nodeId(), id) && i.severity().equals("ERROR"))
                    .map(ValidationIssue::title).distinct().toList();
            normalizedNodes.add(new PipelineNode(node.id(), node.type(), node.name(), node.position(), safe(node.config()), input, output, invalid));
        }
        for (PipelineNode node : byId.values()) if (!topological.contains(node.id())) normalizedNodes.add(node);

        Set<String> connected = connectedToOutputs(byId, incoming);
        for (PipelineNode node : byId.values()) if (!connected.contains(node.id())) {
            issue(issues, "node.unconnected." + node.id(), "GRAPH", "INFO", node.id(), "未连接节点不会进入发布版本", node.name(), "连接到输出或删除该节点");
        }
        PipelineGraph normalized = new PipelineGraph(normalizedNodes, edges.stream()
                .filter(e -> connected.contains(e.source()) && connected.contains(e.target())).toList());
        String hash = hash(normalized, mode);
        boolean valid = issues.stream().noneMatch(issue -> issue.severity().equals("ERROR"));
        Map<String, Object> impact = Map.of(
                "connectedNodes", connected.size(),
                "outputs", outputCount,
                "publishedConsumers", List.of("HugeGraph", "OpenSearch", "平台 Pulsar"),
                "warnings", issues.stream().filter(i -> i.severity().equals("WARNING")).count());
        return new ValidationResult(valid, issues, normalized, impact, hash);
    }

    @SuppressWarnings("unchecked")
    private List<FieldSchema> propagate(PipelineNode node, List<FieldSchema> input, List<FieldSchema> sourceSchema,
                                        PipelineMode mode, Map<String, List<String>> incoming,
                                        Map<String, PipelineNode> byId, List<ValidationIssue> issues) {
        Map<String, Object> config = safe(node.config());
        return switch (node.type()) {
            case "SOURCE" -> sourceSchema;
            case "SELECT" -> {
                Object raw = config.get("fields");
                if (!(raw instanceof List<?> selections) || selections.isEmpty()) {
                    issue(issues, "select.fields." + node.id(), "SCHEMA", "ERROR", node.id(), "请选择输出字段", "选择/重命名节点尚未配置字段。", "在右侧配置字段");
                    yield input;
                }
                List<FieldSchema> selected = new ArrayList<>();
                for (Object value : selections) if (value instanceof Map<?, ?> selection) {
                    String source = String.valueOf(selection.get("source"));
                    String target = selection.get("target") == null ? source : String.valueOf(selection.get("target"));
                    input.stream().filter(field -> field.name().equals(source)).findFirst()
                            .ifPresentOrElse(field -> selected.add(new FieldSchema(target, field.type(), field.nullable(), field.sensitive(), node.id())),
                                    () -> issue(issues, "select.missing." + node.id() + "." + source, "SCHEMA", "ERROR", node.id(), "上游字段已失效", source, "重新选择现有上游字段"));
                }
                yield selected;
            }
            case "CAST" -> input.stream().map(field -> {
                Map<String, String> casts = config.get("casts") instanceof Map<?, ?> map
                        ? map.entrySet().stream().collect(Collectors.toMap(e -> String.valueOf(e.getKey()), e -> String.valueOf(e.getValue()))) : Map.of();
                return new FieldSchema(field.name(), casts.getOrDefault(field.name(), field.type()), field.nullable(), field.sensitive(), node.id());
            }).toList();
            case "DERIVE" -> {
                List<FieldSchema> derived = new ArrayList<>(input);
                String name = text(config.get("name"));
                if (name.isBlank()) issue(issues, "derive.name." + node.id(), "SCHEMA", "ERROR", node.id(), "派生字段缺少名称", "尚未配置派生字段。", "填写字段名称和安全表达式");
                else derived.add(new FieldSchema(name, textOr(config.get("type"), "STRING"), true, false, node.id()));
                yield derived;
            }
            case "AGGREGATE" -> {
                if (mode == PipelineMode.STREAMING && incoming.getOrDefault(node.id(), List.of()).stream()
                        .map(byId::get).noneMatch(parent -> parent != null && parent.type().equals("WINDOW"))) {
                    issue(issues, "aggregate.window." + node.id(), "SEMANTICS", "ERROR", node.id(), "流式聚合前必须有窗口", "无界聚合会产生无界状态。", "在聚合前添加窗口节点");
                }
                yield input;
            }
            case "JOIN" -> {
                if (mode == PipelineMode.STREAMING && !Boolean.TRUE.equals(config.get("boundedDimension"))) {
                    issue(issues, "join.stream." + node.id(), "SEMANTICS", "ERROR", node.id(), "流式 JOIN 仅支持有界维表", "v1 不支持 stream-stream JOIN。", "将辅助源设置为有界 lookup/broadcast 维表");
                }
                yield input.stream().collect(Collectors.toMap(FieldSchema::name, Function.identity(), (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
            }
            case "ONTOLOGY_OBJECT" -> {
                if (text(config.get("objectTypeId")).isBlank() || text(config.get("idField")).isBlank()) {
                    issue(issues, "output.object." + node.id(), "OUTPUT", "ERROR", node.id(), "对象输出配置不完整", "对象类型和对象 ID 字段均为必填。", "配置对象类型与稳定 ID 映射");
                } else if (input.stream().noneMatch(field -> field.name().equals(text(config.get("idField"))))) {
                    issue(issues, "output.object.id." + node.id(), "SCHEMA", "ERROR", node.id(), "对象 ID 字段已失效", text(config.get("idField")), "选择现有上游字段");
                }
                yield List.of();
            }
            case "ONTOLOGY_RELATION" -> {
                if (text(config.get("relationTypeId")).isBlank() || text(config.get("sourceIdField")).isBlank() || text(config.get("targetIdField")).isBlank()) {
                    issue(issues, "output.relation." + node.id(), "OUTPUT", "ERROR", node.id(), "关系输出配置不完整", "关系类型和两端 ID 字段均为必填。", "配置关系端点映射");
                }
                yield List.of();
            }
            default -> input;
        };
    }

    private List<String> topological(Set<String> nodeIds, Map<String, List<String>> incoming,
                                     Map<String, List<String>> outgoing) {
        Map<String, Integer> degree = nodeIds.stream().collect(Collectors.toMap(Function.identity(), id -> incoming.getOrDefault(id, List.of()).size()));
        ArrayDeque<String> queue = new ArrayDeque<>();
        degree.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).sorted().forEach(queue::add);
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            result.add(current);
            for (String target : outgoing.getOrDefault(current, List.of())) {
                int next = degree.computeIfPresent(target, (ignored, value) -> value - 1);
                if (next == 0) queue.add(target);
            }
        }
        return result;
    }

    private Set<String> connectedToOutputs(Map<String, PipelineNode> nodes, Map<String, List<String>> incoming) {
        Set<String> connected = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        nodes.values().stream().filter(node -> OUTPUT_TYPES.contains(node.type())).map(PipelineNode::id).sorted().forEach(queue::add);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!connected.add(current)) continue;
            incoming.getOrDefault(current, List.of()).forEach(queue::add);
        }
        return connected;
    }

    private String hash(PipelineGraph graph, PipelineMode mode) {
        try {
            Map<String, Object> canonical = new LinkedHashMap<>();
            canonical.put("mode", mode);
            canonical.put("nodes", graph.nodes().stream().sorted(Comparator.comparing(PipelineNode::id)).toList());
            canonical.put("edges", graph.edges().stream().sorted(Comparator.comparing(PipelineEdge::id)).toList());
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        } catch (JsonProcessingException | NoSuchAlgorithmException cause) {
            throw new IllegalStateException("Cannot hash Pipeline IR", cause);
        }
    }

    private NodeType type(String type, String label, String category, List<PipelineMode> modes, int minInputs,
                          int maxInputs, boolean source, boolean output, String description) {
        return new NodeType(type, label, category, modes, minInputs, maxInputs, source, output, description);
    }

    private void issue(List<ValidationIssue> issues, String id, String category, String severity, String nodeId,
                       String title, String detail, String recovery) {
        issues.add(new ValidationIssue(id, category, severity, nodeId, title, detail, recovery));
    }

    private Map<String, Object> safe(Map<String, Object> value) { return value == null ? Map.of() : value; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String textOr(Object value, String fallback) { String text = text(value); return text.isBlank() ? fallback : text; }
}
