package com.hezhangjian.ontology.core.dashboards;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DashboardModels {
    private DashboardModels() { }

    public record Actor(String id, String name, List<String> roles) {
        boolean builder() { return roles.contains("Builder") || roles.contains("Admin"); }
    }

    public record DashboardCreateRequest(String name, String description, String visibility,
                                         String refreshPolicy, List<String> tags) { }

    public record DashboardPatchRequest(String name, String description, String visibility,
                                        String refreshPolicy, List<String> tags) { }

    public record DashboardSummary(UUID id, String name, String description, String lifecycle,
                                   Integer currentVersion, int pageCount, int widgetCount,
                                   String ownerName, String visibility, String refreshPolicy,
                                   String healthStatus, boolean favorite, long etag,
                                   Instant updatedAt, Instant lastPublishedAt) { }

    public record DashboardDetail(DashboardSummary summary, DashboardVersionView currentVersion,
                                  DashboardDraftView activeDraft, String accessRole) { }

    public record DashboardDefinition(int schemaVersion, List<DashboardPage> pages,
                                      List<DashboardDataSource> dataSources,
                                      List<DashboardWidget> widgets,
                                      List<DashboardFilterVariable> filters,
                                      List<DashboardFilterBinding> filterBindings,
                                      Map<String, Object> settings) { }

    public record DashboardPage(UUID id, String name, String description, int order) { }

    public record DashboardDataSource(UUID id, String name, String kind, UUID objectTypeId,
                                      UUID referenceId, Integer referenceVersion,
                                      Map<String, Object> query, Long ontologyRevision) { }

    public record DashboardWidget(UUID id, UUID pageId, UUID dataSourceId, String type,
                                  String title, String description, Map<String, Object> layout,
                                  Map<String, Object> config, Map<String, Object> interaction) { }

    public record DashboardFilterVariable(UUID id, String name, String valueType, String controlType,
                                          String scope, UUID scopeId, Object defaultValue,
                                          boolean required, boolean allowEmpty, boolean sensitive,
                                          String applyMode) { }

    public record DashboardFilterBinding(UUID filterId, UUID dataSourceId, UUID propertyId,
                                         String operator) { }

    public record DashboardDraftView(UUID id, UUID dashboardId, UUID baseVersionId,
                                     DashboardDefinition definition, long etag, String status,
                                     String updatedBy, Instant updatedAt) { }

    public record DashboardValidationIssue(String severity, String code, String path, String message) { }

    public record DashboardValidationResult(boolean valid, String status,
                                            List<DashboardValidationIssue> issues,
                                            int estimatedCost, String definitionHash) { }

    public record DashboardVersionView(UUID id, UUID dashboardId, int version,
                                       DashboardDefinition definition, int schemaVersion,
                                       long ontologyRevision, String queryPlanHash,
                                       String publishedByName, String releaseNotes,
                                       String healthStatus, Instant publishedAt) { }

    public record PublishRequest(String releaseNotes) { }

    public record EditLockRequest(Boolean force) { }

    public record DashboardEditLock(UUID dashboardId, String holderId, String holderName,
                                    UUID leaseToken, Instant acquiredAt, Instant expiresAt,
                                    boolean editable) { }

    public record DashboardPermission(String subjectType, String subjectId, String role) { }

    public record DashboardPermissionsRequest(List<DashboardPermission> permissions) { }

    public record DashboardHealth(String status, List<Map<String, Object>> issues) { }

    public record DashboardUsage(long opens, long queryRuns, long cacheHits,
                                 long failedWidgets, Double p95DurationMs) { }

    public record DashboardQueryPlanView(UUID id, UUID dashboardId, UUID versionId,
                                         String planHash, long ontologyRevision,
                                         long policyRevision, int estimatedCost,
                                         Instant createdAt) { }

    public record DashboardExecuteRequest(UUID pageId, List<UUID> widgetIds,
                                          Map<String, Object> filters, UUID refreshId) { }

    public record DashboardWidgetResult(UUID widgetId, String status, String kind,
                                        Object data, boolean cacheHit, boolean suppressed,
                                        Instant queriedAt, Instant watermark,
                                        String correlationId, String safeError) { }

    public record DashboardBatchResult(UUID queryRunId, String status,
                                       List<DashboardWidgetResult> widgets,
                                       int cacheHits, Instant watermark,
                                       String correlationId) { }

    public record FilterOptionsRequest(UUID dataSourceId, UUID propertyId, String search,
                                       Map<String, Object> filters) { }

    public record DrilldownRequest(UUID widgetId, Object value, Map<String, Object> filters) { }

    public record DrilldownToken(String token, Instant expiresAt, String targetKind) { }

    public record DashboardVersionDiff(int fromVersion, int toVersion, String fromHash,
                                       String toHash, Map<String, Object> changes) { }
}
