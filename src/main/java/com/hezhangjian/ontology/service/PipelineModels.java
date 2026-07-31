package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PipelineModels {
    private PipelineModels() { }

    public enum PipelineMode { BATCH, STREAMING }
    public enum PipelineLifecycle { DRAFT, IN_REVIEW, PUBLISHED, PAUSED, ARCHIVED }
    public enum PipelineRunStatus { NEVER_RUN, HEALTHY, RUNNING, LIVE, DEGRADED, FAILED }

    public record Actor(String id, String name, boolean admin) { }

    public record Position(double x, double y) { }

    public record PipelineNode(String id, String type, String name, Position position,
                               Map<String, Object> config, List<FieldSchema> inputSchema,
                               List<FieldSchema> outputSchema, List<String> invalidReasons) { }

    public record PipelineEdge(String id, String source, String target) { }

    public record FieldSchema(String name, String type, boolean nullable, boolean sensitive,
                              String sourceNodeId) { }

    public record PipelineGraph(List<PipelineNode> nodes, List<PipelineEdge> edges) { }

    public record RuntimeSettings(int parallelism, long checkpointIntervalMs, int restartAttempts,
                                  String offsetPolicy, String eventTimeField, long watermarkDelayMs) { }

    public record ScheduleSettings(String type, String cronExpression, Instant runAt,
                                   String concurrencyPolicy, boolean enabled) { }

    public record PipelineDraft(PipelineGraph graph, RuntimeSettings runtime, ScheduleSettings schedule,
                                @JsonIgnore Integer baseVersion, @JsonIgnore long etag,
                                String updatedBy, Instant updatedAt) { }

    public record Pipeline(UUID id, String name, String description, String template, PipelineMode mode,
                           PipelineLifecycle lifecycle, PipelineRunStatus runStatus,
                           UUID dataSourceId, String dataSourceName, UUID sourceAssetId, String sourceAssetName,
                           String targetSummary, String scheduleSummary, String ownerId, String ownerName,
                           @JsonIgnore Integer publishedVersion, @JsonIgnore long version, Instant lastRunAt,
                           Instant createdAt, Instant updatedAt, PipelineDraft draft) { }

    public record PipelinePage(List<Pipeline> items, int page, int size, long total,
                               Map<String, Integer> counts, Map<String, String> appliedFilters) { }

    public record CreatePipelineRequest(String name, String description, String template, PipelineMode mode,
                                        UUID dataSourceId, UUID sourceAssetId, String ownerId, String ownerName) { }

    public record UpdateDraftRequest(PipelineGraph graph, RuntimeSettings runtime, ScheduleSettings schedule,
                                     String name, String description) { }

    public record ValidationIssue(String id, String category, String severity, String nodeId,
                                  String title, String detail, String recoveryAction) { }

    public record ValidationResult(boolean valid, List<ValidationIssue> issues,
                                   PipelineGraph normalizedGraph, Map<String, Object> impact,
                                   String contentHash) { }

    public record NodeType(String type, String label, String category, List<PipelineMode> modes,
                           int minInputs, int maxInputs, boolean source, boolean output,
                           String description) { }

    public record PreviewRequest(String nodeId, int limit) { }

    public record PreviewRun(UUID id, UUID pipelineId, long draftEtag, String nodeId, String status,
                             String flinkJobId, List<Map<String, Object>> rows, List<FieldSchema> schema,
                             Map<String, Object> diagnostic, Instant startedAt, Instant completedAt,
                             Instant expiresAt) { }

    public record PreviewResultRequest(List<Map<String, Object>> rows, long sizeBytes,
                                       String status, Map<String, Object> diagnostic) { }

    public record PipelineVersion(UUID id, UUID pipelineId, int version, PipelineGraph graph,
                                  Map<String, Object> pipelineIr, Map<String, Object> jobSpec,
                                  String contentHash, List<ValidationIssue> validation,
                                  String publishedBy, String publishedByName, Instant publishedAt) { }

    public record PublishRequest(boolean acknowledgeWarnings, boolean startAfterPublish) { }

    public record PipelineRun(UUID id, UUID pipelineId, String pipelineName, @JsonIgnore UUID pipelineVersionId,
                              @JsonIgnore Integer pipelineVersion, UUID retryOf, String triggerType, String status,
                              String flinkJobId, String correlationId, long readCount, long writtenCount,
                              long rejectedCount, String projectionStatus, String savepointPath,
                              Map<String, Object> diagnostic, String requestedByName,
                              Instant startedAt, Instant completedAt, Instant updatedAt) { }

    public record PipelineRunPage(List<PipelineRun> items, int page, int size, long total) { }

    public record RunEvent(long sequence, String eventType, String status, String message,
                           Map<String, Object> safeDetails, Instant occurredAt) { }

    public record RunStage(UUID id, int order, String type, String status, String correlationId,
                           String flinkJobId, Map<String, Object> eventPosition, long readCount,
                           long writtenCount, long rejectedCount, Instant startedAt, Instant completedAt) { }

    public record RunDetail(PipelineRun run, List<RunStage> stages, List<RunEvent> events,
                            Map<String, Object> metrics, List<Map<String, Object>> logs) { }

    public record SavepointRequest(boolean drain) { }

    public record OffsetResetRequest(String position, Instant timestamp, Map<String, Long> specificOffsets,
                                     boolean acknowledgeDuplicateOrLossRisk) { }

    public record RuntimeSourceGrant(String sourceType, Map<String, Object> sourceConfig,
                                     Map<String, String> credential) { }

    public record WorkloadBundle(UUID workloadId, UUID workspaceId, String kind,
                                 String sourceType, Map<String, Object> sourceConfig,
                                 Map<String, String> credential, Map<String, RuntimeSourceGrant> sources,
                                 PipelineGraph graph, RuntimeSettings runtime, String targetTopic,
                                 String controlTopic, String correlationId, String previewNodeId,
                                 Integer previewLimit, Instant expiresAt) { }

    public record RuntimeProgressRequest(String phase, long readCount, long writtenCount,
                                         long rejectedCount, String message,
                                         Map<String, Object> safeDetails) { }

    public record PipelineControlEvent(UUID eventId, UUID workloadId, String workloadKind,
                                       String eventType, String phase, long readCount,
                                       long writtenCount, long rejectedCount, String message,
                                       Map<String, Object> safeDetails, List<Map<String, Object>> rows,
                                       String status, Map<String, Object> diagnostic, Instant occurredAt) { }
}
