package com.hezhangjian.ontology.core.dashboards;

import static com.hezhangjian.ontology.core.dashboards.DashboardModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardPolicyTest {
    private final DashboardPolicy policy = new DashboardPolicy(new ObjectMapper());
    private final UUID pageId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID objectTypeId = UUID.randomUUID();

    @Test
    void acceptsTypedServerSideDashboard() {
        DashboardValidationResult result = policy.validate(definition(List.of(widget("METRIC", 0, 0, 6, 3, Map.of("aggregation", "count")))));
        assertThat(result.valid()).isTrue();
        assertThat(result.definitionHash()).hasSize(64);
    }

    @Test
    void rejectsOverlappingDesktopLayout() {
        DashboardWidget first = widget("METRIC", 0, 0, 8, 4, Map.of("aggregation", "count"));
        DashboardWidget second = widget("OBJECT_TABLE", 4, 2, 8, 4, Map.of());
        DashboardValidationResult result = policy.validate(definition(List.of(first, second)));
        assertThat(result.issues()).extracting(DashboardValidationIssue::code).contains("LAYOUT_OVERLAP");
    }

    @Test
    void rejectsHtmlAndScriptsInMarkdown() {
        DashboardWidget markdown = new DashboardWidget(UUID.randomUUID(), pageId, null, "MARKDOWN", "说明", "",
                layout(0, 0, 12, 3), Map.of("markdown", "<iframe src='https://example.test'></iframe>"), Map.of());
        DashboardValidationResult result = policy.validate(definition(List.of(markdown)));
        assertThat(result.issues()).extracting(DashboardValidationIssue::code).contains("UNSAFE_MARKDOWN");
    }

    @Test
    void rejectsUnsafeCalculatedMetric() {
        DashboardValidationResult result = policy.validate(definition(List.of(widget("METRIC", 0, 0, 6, 3,
                Map.of("aggregation", "count", "expression", "system(metric)")))));
        assertThat(result.issues()).extracting(DashboardValidationIssue::code).contains("CALCULATED_METRIC");
    }

    @Test
    void requiresExactVersionForReferencedSource() {
        DashboardDataSource source = new DashboardDataSource(sourceId, "固定探索", "EXPLORATION", objectTypeId,
                UUID.randomUUID(), null, Map.of(), 5L);
        DashboardDefinition definition = new DashboardDefinition(1, List.of(new DashboardPage(pageId, "概览", "", 0)),
                List.of(source), List.of(widget("METRIC", 0, 0, 6, 3, Map.of("aggregation", "count"))),
                List.of(), List.of(), Map.of());
        assertThat(policy.validate(definition).issues()).extracting(DashboardValidationIssue::code).contains("EXACT_VERSION");
    }

    private DashboardDefinition definition(List<DashboardWidget> widgets) {
        DashboardDataSource source = new DashboardDataSource(sourceId, "员工", "OBJECT_SET", objectTypeId, null, null,
                Map.of("objectTypeId", objectTypeId, "where", Map.of(), "sort", List.of(), "pageSize", 50, "columns", List.of()), 5L);
        return new DashboardDefinition(1, List.of(new DashboardPage(pageId, "概览", "", 0)), List.of(source), widgets,
                List.of(), List.of(), Map.of("timezone", "Asia/Shanghai"));
    }

    private DashboardWidget widget(String type, int x, int y, int w, int h, Map<String, Object> config) {
        return new DashboardWidget(UUID.randomUUID(), pageId, sourceId, type, type, "", layout(x, y, w, h), config, Map.of());
    }

    private Map<String, Object> layout(int x, int y, int w, int h) {
        return Map.of("desktop", Map.of("x", x, "y", y, "w", w, "h", h),
                "tablet", Map.of("x", 0, "y", y, "w", Math.min(w, 12), "h", h),
                "mobile", Map.of("x", 0, "y", y, "w", 1, "h", h));
    }
}
