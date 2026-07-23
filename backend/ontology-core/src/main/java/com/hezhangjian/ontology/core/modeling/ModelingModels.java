package com.hezhangjian.ontology.core.modeling;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ModelingModels {
    private ModelingModels() { }

    public enum ResourceKind { OBJECT_TYPE, LINK_TYPE, INTERFACE, ACTION, FUNCTION }

    public record Actor(String id, String name, boolean admin) { }

    public record PropertyDraft(String apiName, String displayName, String description, String valueType,
                                boolean required, boolean primaryKey, boolean titleProperty,
                                boolean searchable, boolean filterable, boolean sortable,
                                boolean sensitive, String maskingPolicy, String analyzer,
                                String sourceField, List<String> enumValues) { }

    public record ParameterDraft(String apiName, String displayName, String valueType,
                                 boolean required, boolean sensitive, Object defaultValue) { }

    public record ResourceDraftRequest(
            String displayName, String apiName, String description, String maturity,
            String ownerId, String ownerName, List<String> tags, boolean promoted,
            String sourceMode, UUID primaryPipelineId, List<PropertyDraft> properties,
            UUID leftObjectTypeId, UUID rightObjectTypeId, UUID targetObjectTypeId, String cardinality,
            String leftDisplayName, String rightDisplayName, UUID sourcePropertyId,
            String operation, String approvalPolicy, List<ParameterDraft> parameters,
            List<Map<String, Object>> rules, Map<String, Object> submitCondition,
            List<Map<String, Object>> slots, List<Map<String, Object>> implementations,
            String outputType, Map<String, Object> queryDsl, List<UUID> dependencyIds,
            Integer timeoutMs, Integer maxResults, Integer cacheSeconds) { }

    public record PropertyView(UUID id, String apiName, String displayName, String description,
                               String valueType, boolean required, boolean primaryKey,
                               boolean titleProperty, boolean searchable, boolean filterable,
                               boolean sortable, boolean sensitive, String physicalKey,
                               String sourceField) { }

    public record ResourceView(UUID id, ResourceKind kind, String apiName, String displayName,
                               String description, String physicalKey, String ownerId, String ownerName,
                               String maturity, boolean promoted, List<String> tags, String lifecycle,
                               int version, Integer activeVersion, Long publishedRevision, long etag,
                               Map<String, Object> definition, List<PropertyView> properties,
                               Instant createdAt, Instant updatedAt) { }

    public record ModelingSummary(long ontologyRevision, Instant lastPublishedAt, String publishHealth,
                                  long unpublishedProposals, long criticalIssues, long projectionFailures,
                                  long pendingReviews, Map<String, Long> resourceCounts,
                                  List<ResourceView> recentResources) { }

    public record SearchResult(UUID id, ResourceKind kind, String apiName, String displayName,
                               String description, String ownerName, List<String> tags, String lifecycle) { }

    public record ValidationIssue(String code, String severity, UUID resourceId,
                                  String field, String message, String recoveryAction) { }

    public record ProposalRequest(String title, String description, List<UUID> resourceIds) { }

    public record ProposalView(UUID id, String title, String description, String status,
                               long baselineRevision, String riskLevel, List<ValidationIssue> validation,
                               Map<String, Object> impact, List<ResourceView> resources,
                               String createdByName, Instant createdAt, Instant updatedAt,
                               Instant submittedAt, Long publishedRevision) { }

    public record ReviewRequest(String decision, String comment) { }

    public record DeploymentStep(int order, String name, String status, String externalResource,
                                 String safeError, Instant startedAt, Instant completedAt) { }

    public record DeploymentView(UUID id, UUID proposalId, long targetRevision, String status,
                                 String currentStep, int attempt, String safeError,
                                 Instant createdAt, Instant startedAt, Instant completedAt,
                                 List<DeploymentStep> steps) { }

    public record HealthIssue(UUID id, String severity, String category, UUID resourceId,
                              String resourceName, String title, String evidence,
                              String recommendation, String ownerName, String status,
                              Instant firstSeenAt, Instant lastSeenAt) { }

    public record HistoryEntry(long revision, String status, Instant activatedAt,
                               Instant createdAt, long resourceCount, UUID proposalId,
                               String proposalTitle) { }

    public record ActionPreviewRequest(Map<String, Object> parameters, String objectId, Long objectVersion) { }

    public record ActionPreview(UUID actionId, String previewToken, Instant expiresAt,
                                List<Map<String, Object>> edits, List<String> requiredApprovals,
                                Map<String, Object> diff) { }

    public record ActionExecuteRequest(String previewToken, String idempotencyKey) { }

    public record ActionExecution(UUID id, UUID actionId, UUID previewId, String status,
                                  String correlationId, String safeError,
                                  Instant submittedAt, Instant completedAt) { }

    public record FunctionTestRequest(Map<String, Object> inputs) { }

    public record FunctionTestResult(UUID functionId, String versionBinding, String outputType,
                                     Object result, long durationMs, List<UUID> dependencies,
                                     boolean callerPermissionsApplied) { }
}
