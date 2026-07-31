package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ModelingModels {
    private ModelingModels() { }

    public enum ResourceKind { OBJECT_TYPE, LINK_TYPE, INTERFACE, ACTION, FUNCTION }

    public record PropertyDraft(String apiName, String displayName, String description, String valueType,
                                boolean required, boolean primaryKey, boolean titleProperty,
                                boolean searchable, boolean filterable, boolean sortable,
                                boolean sensitive, String maskingPolicy, String analyzer,
                                String sourceField, List<String> enumValues, Boolean actionWritable) { }

    public record ParameterDraft(String apiName, String displayName, String valueType,
                                 boolean required, boolean sensitive, Object defaultValue) { }

    public record ResourceDraftRequest(
            String displayName, @JsonProperty("id") String apiName, String description, String maturity,
            List<String> tags, boolean promoted,
            String sourceMode, UUID primaryPipelineId, String datasetId,
            Map<String, String> datasetMapping, List<PropertyDraft> properties,
            UUID leftObjectTypeId, UUID rightObjectTypeId, UUID targetObjectTypeId, String cardinality,
            String leftDisplayName, String rightDisplayName, UUID sourcePropertyId,
            String operation, List<ParameterDraft> parameters,
            List<Map<String, Object>> rules,
            List<Map<String, Object>> slots, List<Map<String, Object>> implementations,
            String outputType, Map<String, Object> queryDsl, List<UUID> dependencyIds,
            Integer timeoutMs, Integer maxResults, Integer cacheSeconds) { }

    public record ResourceIdentityRequest(
            @JsonProperty("id") String apiName, String displayName, String description) { }

    public record PropertyView(UUID id, String apiName, String displayName, String description,
                               String valueType, boolean required, boolean primaryKey,
                               boolean titleProperty, boolean searchable, boolean filterable,
                               boolean sortable, boolean sensitive, boolean actionWritable, String physicalKey,
                               String sourceField) { }

    public record MappingView(
            UUID propertyId,
            String propertyApiName,
            String propertyDisplayName,
            String sourceField,
            String sinkNodeId,
            List<String> transformPath) { }

    public record ObjectTypeBackingView(
            String sourceMode,
            UUID pipelineId,
            String pipelineName,
            Integer pipelineVersion,
            String pipelineLifecycle,
            List<MappingView> mappings,
            String lastRunStatus,
            String projectionStatus,
            Instant lastRunAt,
            long mappedPropertyCount,
            long propertyCount,
            String status) { }

    public record ResourceView(@JsonProperty("resourceId") UUID id, ResourceKind kind,
                               @JsonProperty("id") String apiName, String displayName,
                               String description, String physicalKey,
                               String maturity, boolean promoted, List<String> tags, String lifecycle,
                               @JsonIgnore int version, @JsonIgnore Integer activeVersion,
                               @JsonIgnore long etag,
                               Map<String, Object> definition, List<PropertyView> properties,
                               Instant createdAt, Instant updatedAt) { }

    public record ModelingSummary(String health, long criticalIssues, long projectionFailures,
                                  Map<String, Long> resourceCounts,
                                  Map<String, Long> objectInstanceCounts,
                                  Map<String, Long> relationInstanceCounts,
                                  List<ResourceView> recentResources) { }

    public record SearchResult(@JsonIgnore UUID id, ResourceKind kind,
                               @JsonProperty("id") String apiName, String displayName,
                               String description, List<String> tags, String lifecycle) { }

    public record HealthIssue(UUID id, String severity, String category, UUID resourceId,
                              String resourceName, String title, String evidence,
                              String recommendation, String status,
                              Instant firstSeenAt, Instant lastSeenAt) { }

    public record ActionPreviewRequest(Map<String, Object> parameters, String objectId) { }

    public record ActionPreview(UUID id, UUID actionTypeId, @JsonIgnore int actionVersion, String token,
                                Instant expiresAt, List<Map<String, Object>> visibleDiff) { }

    public record ActionExecuteRequest(String previewToken, String idempotencyKey) { }

    public record ActionExecution(UUID id, UUID actionTypeId, @JsonIgnore int actionVersion, UUID previewId,
                                  String status, String correlationId,
                                  String traceId, String safeError,
                                  Instant submittedAt, Instant completedAt) { }

    public record FunctionTestRequest(Map<String, Object> inputs) { }

    public record FunctionExecution(UUID id, UUID functionId, @JsonIgnore int functionVersion,
                                    Object result, long durationMs,
                                    String traceId, boolean cacheHit,
                                    List<Map<String, Object>> stepDiagnostics) { }
}
