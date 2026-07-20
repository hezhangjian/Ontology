package com.hezhangjian.ontology.core.connections;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConnectionModels {
    private ConnectionModels() {
    }

    public enum DataSourceType { S3_CSV, MYSQL, POSTGRESQL, KAFKA, EXTERNAL_PULSAR }
    public enum ConnectionStatus { UNTESTED, TESTING, HEALTHY, HEALTHY_RESTRICTED, ERROR, DISABLED }
    public enum SyncStatus { NO_TASKS, IDLE, RUNNING, STREAMING, PARTIAL_FAILURE, ALL_FAILURE }

    public record CredentialInput(String mode, String name, UUID existingSecretRef,
                                  Map<String, String> fileRefs, Map<String, String> values) {
        public Map<String, String> safeValues() { return values == null ? Map.of() : values; }
        public Map<String, String> safeFileRefs() { return fileRefs == null ? Map.of() : fileRefs; }
    }

    public record CredentialSummary(UUID id, String name, String provider, String credentialType,
                                    String status, int referenceCount, Instant createdAt, Instant rotatedAt) {
    }

    public record TestRequest(DataSourceType type, Map<String, Object> config, CredentialInput credential) {
    }

    public record CreateRequest(String name, String description, DataSourceType type, String ownerId,
                                String ownerName, List<String> tags, Map<String, Object> config,
                                CredentialInput credential, String testToken) {
    }

    public record UpdateRequest(String name, String description, String ownerId, String ownerName,
                                List<String> tags, Map<String, Object> config) {
    }

    public record RotateCredentialRequest(CredentialInput credential) {
    }

    public record TestStage(String stage, String status, String message, long durationMs) {
    }

    public record Diagnostic(String stage, Instant occurredAt, String reason, UUID requestId,
                             String suggestion, String technicalDetail) {
    }

    public record TestResult(UUID requestId, ConnectionStatus status, List<TestStage> stages,
                             int assetCount, String configFingerprint, String testToken,
                             Instant expiresAt, List<DiscoveredAsset> discoveredAssets) {
    }

    public record DataSource(UUID id, String name, String description, DataSourceType type,
                             String ownerId, String ownerName, List<String> tags, Map<String, Object> config,
                             CredentialSummary credential, ConnectionStatus status, SyncStatus syncStatus,
                             int assetCount, Instant lastCheckedAt, Diagnostic lastError, long version,
                             int pipelineReferenceCount, int activeRunCount, Instant createdAt, Instant updatedAt) {
    }

    public record DataSourcePage(List<DataSource> items, int page, int size, long total,
                                 Map<String, Integer> counts, Map<String, Object> filters) {
    }

    public record AssetField(UUID id, String name, String inferredType, String originalType,
                             boolean nullable, boolean sensitive, boolean primaryKeyCandidate,
                             String sampleValue) {
    }

    public record DiscoveredField(String name, String inferredType, String originalType,
                                  boolean nullable, boolean primaryKeyCandidate, String sampleValue) {
    }

    public record DiscoveredAsset(String stableKey, String name, String fullPath, String parentPath,
                                  String assetType, Long sizeBytes, Long estimatedRows,
                                  Integer partitionCount, String permissionStatus,
                                  List<DiscoveredField> fields) {
    }

    public record DataSourceAsset(UUID id, String name, String fullPath, String parentPath,
                                  String assetType, String status, String schemaStatus, String schemaHash,
                                  int schemaVersion, int fieldCount, Long sizeBytes, Long estimatedRows,
                                  Integer partitionCount, String permissionStatus, boolean usedByPipeline,
                                  Instant discoveredAt, List<AssetField> fields) {
    }

    public record AssetPage(List<DataSourceAsset> items, int page, int size, long total) {
    }

    public record DiscoveryRun(UUID taskId, String status, int discoveredCount,
                               Instant startedAt, Instant completedAt, Diagnostic diagnostic) {
    }

    public record AssetPreview(List<String> columns, List<Map<String, Object>> rows,
                               boolean truncated, int maxBytes) {
    }

    public record PipelineSummary(UUID id, String name, String sourceAsset, String mode,
                                  String status, String ownerName, Instant recentRunAt) {
    }

    public record PipelineRunSummary(UUID id, String pipelineName, String sourceAsset,
                                     String triggerType, String status, Instant startedAt,
                                     Long durationMs, long readCount, long writtenCount,
                                     long rejectedCount, String flinkJobId) {
    }

    public record AuditEvent(UUID id, String action, String actorName, Instant occurredAt, String summary) {
    }

    public record Overview(DataSource connection, List<TestStage> health,
                           Map<String, Integer> assetSummary, Map<String, Integer> pipelineSummary,
                           List<PipelineRunSummary> recentRuns, List<AuditEvent> recentActivity,
                           Map<String, Diagnostic> sectionErrors) {
    }

    public record AssetUsage(List<PipelineSummary> pipelines, int restrictedReferences) {
    }

    public record Actor(String id, String name, boolean admin) {
    }
}
