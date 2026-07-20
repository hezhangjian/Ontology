package com.hezhangjian.ontology.core.dashboards;

import static com.hezhangjian.ontology.core.dashboards.DashboardModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class DashboardPolicy {
    private static final Set<String> SOURCE_KINDS = Set.of("OBJECT_SET", "EXPLORATION", "OBJECT_LIST", "FUNCTION");
    private static final Set<String> WIDGET_TYPES = Set.of(
            "METRIC", "LINE", "AREA", "BAR", "STACKED_BAR", "PIE", "DONUT", "SCATTER",
            "OBJECT_TABLE", "PIVOT", "MARKDOWN", "FILTER", "SECTION");
    private static final Set<String> AGGREGATIONS = Set.of("count", "sum", "avg", "min", "max", "approx_distinct");
    private static final Set<String> EXPRESSION_FUNCTIONS = Set.of("round", "coalesce", "percent");
    private final ObjectMapper objectMapper;

    DashboardPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    DashboardValidationResult validate(DashboardDefinition definition) {
        List<DashboardValidationIssue> issues = new ArrayList<>();
        if (definition == null || definition.schemaVersion() != 1) {
            issues.add(error("SCHEMA_VERSION", "definition", "看板定义必须使用 schema version 1"));
            return result(definition, issues, 0);
        }
        List<DashboardPage> pages = safe(definition.pages());
        List<DashboardDataSource> sources = safe(definition.dataSources());
        List<DashboardWidget> widgets = safe(definition.widgets());
        List<DashboardFilterVariable> filters = safe(definition.filters());
        List<DashboardFilterBinding> bindings = safe(definition.filterBindings());
        if (pages.isEmpty() || pages.size() > 10) issues.add(error("PAGE_LIMIT", "pages", "看板必须包含 1—10 个页面"));
        if (widgets.size() > 100) issues.add(error("WIDGET_LIMIT", "widgets", "看板组件总数不能超过 100"));
        if (filters.size() > 10) issues.add(error("FILTER_LIMIT", "filters", "筛选变量不能超过 10 个"));

        Set<UUID> pageIds = uniqueIds(pages.stream().map(DashboardPage::id).toList(), "pages", issues);
        Set<UUID> sourceIds = uniqueIds(sources.stream().map(DashboardDataSource::id).toList(), "dataSources", issues);
        Set<UUID> widgetIds = uniqueIds(widgets.stream().map(DashboardWidget::id).toList(), "widgets", issues);
        Set<UUID> filterIds = uniqueIds(filters.stream().map(DashboardFilterVariable::id).toList(), "filters", issues);
        if (pageIds.size() != pages.size() || widgetIds.size() != widgets.size()) {
            issues.add(error("STABLE_ID", "definition", "页面和组件必须具有唯一稳定 ID"));
        }
        pages.stream().collect(java.util.stream.Collectors.groupingBy(DashboardPage::order, java.util.stream.Collectors.counting()))
                .forEach((order, count) -> { if (count > 1) issues.add(error("PAGE_ORDER", "pages", "页面顺序不能重复")); });

        for (DashboardDataSource source : sources) {
            if (!SOURCE_KINDS.contains(source.kind())) issues.add(error("SOURCE_KIND", "dataSources." + source.id(), "数据源类型不受支持"));
            if (source.objectTypeId() == null) issues.add(error("OBJECT_TYPE", "dataSources." + source.id(), "数据源必须固定对象类型"));
            if (!"OBJECT_SET".equals(source.kind()) && (source.referenceId() == null || source.referenceVersion() == null)) {
                issues.add(error("EXACT_VERSION", "dataSources." + source.id(), "引用数据源必须固定已发布资源版本"));
            }
            if ("OBJECT_SET".equals(source.kind()) && (source.query() == null || source.query().isEmpty())) {
                issues.add(error("QUERY_AST", "dataSources." + source.id(), "Object Set 数据源必须包含类型化查询"));
            }
        }

        Map<UUID, List<DashboardWidget>> byPage = new HashMap<>();
        for (DashboardWidget widget : widgets) {
            if (!WIDGET_TYPES.contains(widget.type())) issues.add(error("WIDGET_TYPE", "widgets." + widget.id(), "组件类型不受支持"));
            if (!pageIds.contains(widget.pageId())) issues.add(error("PAGE_REFERENCE", "widgets." + widget.id(), "组件页面引用无效"));
            if (requiresData(widget.type()) && !sourceIds.contains(widget.dataSourceId())) {
                issues.add(error("SOURCE_REFERENCE", "widgets." + widget.id(), "查询组件必须引用有效数据源"));
            }
            if ("MARKDOWN".equals(widget.type())) validateMarkdown(widget, issues);
            validateAggregation(widget, issues);
            validateLayout(widget, issues);
            byPage.computeIfAbsent(widget.pageId(), ignored -> new ArrayList<>()).add(widget);
        }
        byPage.forEach((pageId, items) -> {
            long count = items.stream().filter(item -> requiresData(item.type())).count();
            if (count > 30) issues.add(error("PAGE_QUERY_LIMIT", "pages." + pageId, "每页查询组件不能超过 30 个"));
            validateOverlap(pageId, items, issues);
        });

        for (DashboardFilterVariable filter : filters) {
            if (filter.sensitive() && filter.defaultValue() != null) {
                issues.add(error("SENSITIVE_DEFAULT", "filters." + filter.id(), "敏感筛选值不能保存在发布定义中"));
            }
            if (!Set.of("GLOBAL", "PAGE", "WIDGET").contains(filter.scope())) issues.add(error("FILTER_SCOPE", "filters." + filter.id(), "筛选作用域无效"));
            if (!Set.of("AUTO", "MANUAL", "DEFERRED").contains(filter.applyMode())) issues.add(error("APPLY_MODE", "filters." + filter.id(), "筛选应用模式无效"));
        }
        for (DashboardFilterBinding binding : bindings) {
            if (!filterIds.contains(binding.filterId()) || !sourceIds.contains(binding.dataSourceId()) || binding.propertyId() == null) {
                issues.add(error("FILTER_BINDING", "filterBindings", "筛选必须通过稳定 ID 显式映射到数据源属性"));
            }
        }
        int cost = widgets.stream().mapToInt(widget -> requiresData(widget.type()) ? 10 : 1).sum() + sources.size() * 5;
        if (cost > 1000) issues.add(error("QUERY_COST", "definition", "查询计划超过允许成本"));
        return result(definition, issues, cost);
    }

    String fingerprint(Object value) {
        try {
            byte[] canonical = objectMapper.writer()
                    .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot fingerprint dashboard definition", exception);
        }
    }

    private DashboardValidationResult result(DashboardDefinition definition,
                                             List<DashboardValidationIssue> issues, int cost) {
        boolean valid = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        return new DashboardValidationResult(valid, valid ? "READY" : "VALIDATION_FAILED",
                List.copyOf(issues), cost, fingerprint(definition == null ? Map.of() : definition));
    }

    private void validateLayout(DashboardWidget widget, List<DashboardValidationIssue> issues) {
        Map<String, Object> layout = widget.layout() == null ? Map.of() : widget.layout();
        for (String device : List.of("desktop", "tablet", "mobile")) {
            Object raw = layout.get(device);
            if (raw == null && !"desktop".equals(device)) continue;
            if (!(raw instanceof Map<?, ?> position)) {
                issues.add(error("LAYOUT", "widgets." + widget.id(), "组件必须包含 desktop 24 列布局"));
                continue;
            }
            int columns = "desktop".equals(device) ? 24 : "tablet".equals(device) ? 12 : 1;
            int x = number(position.get("x"), -1); int y = number(position.get("y"), -1);
            int w = number(position.get("w"), -1); int h = number(position.get("h"), -1);
            if (x < 0 || y < 0 || w < 1 || h < 1 || x + w > columns) {
                issues.add(error("LAYOUT_BOUNDS", "widgets." + widget.id() + "." + device, "组件布局超出栅格边界"));
            }
        }
    }

    private void validateOverlap(UUID pageId, List<DashboardWidget> widgets,
                                 List<DashboardValidationIssue> issues) {
        List<DashboardWidget> ordered = widgets.stream().sorted(Comparator.comparing(item -> item.id().toString())).toList();
        for (int i = 0; i < ordered.size(); i++) {
            Map<?, ?> a = position(ordered.get(i), "desktop");
            if (a == null) continue;
            for (int j = i + 1; j < ordered.size(); j++) {
                Map<?, ?> b = position(ordered.get(j), "desktop");
                if (b != null && overlaps(a, b)) {
                    issues.add(error("LAYOUT_OVERLAP", "pages." + pageId, "desktop 布局中的组件不能重叠"));
                    return;
                }
            }
        }
    }

    private void validateMarkdown(DashboardWidget widget, List<DashboardValidationIssue> issues) {
        String markdown = String.valueOf(widget.config() == null ? "" : widget.config().getOrDefault("markdown", ""));
        String lower = markdown.toLowerCase();
        if (lower.contains("<script") || lower.contains("<iframe") || lower.contains("javascript:") || lower.matches("(?s).*<[^>]+>.*")) {
            issues.add(error("UNSAFE_MARKDOWN", "widgets." + widget.id(), "富文本不允许 HTML、脚本或 iframe"));
        }
    }

    private void validateAggregation(DashboardWidget widget, List<DashboardValidationIssue> issues) {
        if (widget.config() == null) return;
        Object aggregation = widget.config().get("aggregation");
        if (aggregation != null && !AGGREGATIONS.contains(String.valueOf(aggregation))) {
            issues.add(error("AGGREGATION", "widgets." + widget.id(), "聚合函数不受支持"));
        }
        Object expression = widget.config().get("expression");
        if (expression != null) {
            String value = String.valueOf(expression);
            if (value.matches(".*[;{}\\[\\]'\"`].*")
                    || value.contains("/") && value.matches(".*?/\\s*0(?:\\D|$).*")
                    || containsUnknownFunction(value)) {
                issues.add(error("CALCULATED_METRIC", "widgets." + widget.id(), "计算指标包含非法表达式、函数或静态除零"));
            }
        }
    }

    private boolean containsUnknownFunction(String expression) {
        var matcher = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(expression);
        while (matcher.find()) if (!EXPRESSION_FUNCTIONS.contains(matcher.group(1))) return true;
        return false;
    }

    private boolean requiresData(String type) {
        return !Set.of("MARKDOWN", "SECTION", "FILTER").contains(type);
    }

    private Set<UUID> uniqueIds(List<UUID> ids, String path, List<DashboardValidationIssue> issues) {
        Set<UUID> values = new HashSet<>();
        for (UUID id : ids) {
            if (id == null) issues.add(error("STABLE_ID", path, "稳定 ID 不能为空"));
            else values.add(id);
        }
        return values;
    }

    private Map<?, ?> position(DashboardWidget widget, String device) {
        if (widget.layout() == null || !(widget.layout().get(device) instanceof Map<?, ?> value)) return null;
        return value;
    }

    private boolean overlaps(Map<?, ?> a, Map<?, ?> b) {
        int ax = number(a.get("x"), 0), ay = number(a.get("y"), 0), aw = number(a.get("w"), 1), ah = number(a.get("h"), 1);
        int bx = number(b.get("x"), 0), by = number(b.get("y"), 0), bw = number(b.get("w"), 1), bh = number(b.get("h"), 1);
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private DashboardValidationIssue error(String code, String path, String message) {
        return new DashboardValidationIssue("ERROR", code, path, message);
    }

    private <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
}
