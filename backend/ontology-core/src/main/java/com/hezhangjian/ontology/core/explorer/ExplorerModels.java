package com.hezhangjian.ontology.core.explorer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExplorerModels {
    private ExplorerModels() { }

    public record Actor(String id, String name, List<String> roles) {
        boolean admin() { return roles.contains("Admin"); }
        boolean builder() { return admin() || roles.contains("Builder"); }
    }

    public record PropertyDefinition(UUID id, String apiName, String physicalKey, String displayName, String valueType,
                                     boolean primaryKey, boolean titleProperty, boolean searchable,
                                     boolean filterable, boolean sortable, boolean sensitive) { }

    public record ObjectTypeDefinition(UUID id, String apiName, String physicalKey, String displayName, String maturity,
                                       long ontologyRevision, List<PropertyDefinition> properties) { }

    public record SortClause(UUID propertyId, String direction) { }

    public record ObjectSetRequest(UUID objectTypeId, Map<String, Object> where, List<SortClause> sort,
                                   Integer pageSize, String cursor, List<UUID> columns) { }

    public record ObjectSummary(String objectId, String title, String objectTypeApiName,
                                UUID objectTypeId, long version, long ontologyRevision,
                                Map<String, Object> properties, List<UUID> redactedFields,
                                String quality, Instant updatedAt) { }

    public record ObjectSetPage(UUID objectTypeId, String objectTypeName, long ontologyRevision,
                                long visibleCount, boolean countLowerBound, List<ObjectSummary> items,
                                String nextCursor, String queryFingerprint, Instant indexUpdatedAt,
                                List<PropertyDefinition> properties) { }

    public record FacetRequest(ObjectSetRequest query, List<UUID> propertyIds) { }

    public record FacetBucket(Object value, long count) { }

    public record FacetResult(UUID propertyId, String displayName, List<FacetBucket> buckets) { }

    public record AggregationBucket(Object value, long count, double metric) { }

    public record SearchRequest(String query, String mode, String tab, Integer size) { }

    public record SearchResponse(List<ObjectSummary> objects, List<ObjectTypeDefinition> objectTypes,
                                 List<ExplorationView> explorations, List<ObjectListView> lists,
                                 long visibleObjectCount, Instant indexUpdatedAt) { }

    public record ExplorerHome(List<ObjectTypeDefinition> objectTypes, Map<UUID, Long> objectCounts,
                               List<ObjectSummary> recentObjects,
                               List<ExplorationView> explorations, List<ObjectListView> lists,
                               String searchStatus, Instant indexUpdatedAt) { }

    public record ObjectDetail(String objectId, String title, ObjectTypeDefinition objectType,
                               long version, String etag, long ontologyRevision,
                               Map<String, Object> properties, List<UUID> redactedFields,
                               String quality, Instant updatedAt) { }

    public record LinkRequest(String direction, List<UUID> linkTypeIds, Integer pageSize, String cursor) { }

    public record ObjectLink(String relationId, UUID linkTypeId, String linkTypeName, String direction,
                             String targetObjectId, UUID targetObjectTypeId, String targetTitle,
                             Map<String, Object> edgeProperties) { }

    public record LinkPage(List<ObjectLink> items, String nextCursor, long visibleCount) { }

    public record Capability(UUID id, String kind, String displayName, String apiName,
                             int version, boolean executable, boolean previewRequired) { }

    public record CapabilityResponse(List<Capability> actions, List<Capability> functions,
                                     List<String> openTo) { }

    public record ActivityItem(String kind, String status, String summary, String actor,
                               String correlationId, Instant occurredAt) { }

    public record ProvenanceView(String objectId, String primaryPipeline, Integer pipelineVersion,
                                 String projectionStatus, long ontologyRevision, String sourceAsset,
                                 String indexStatus, List<Map<String, Object>> fieldLineage) { }

    public record ExplorationRequest(String name, String description, UUID objectTypeId,
                                     ObjectSetRequest query, List<UUID> columns, String perspective,
                                     Map<String, Object> viewConfig, String visibility) { }

    public record ExplorationView(UUID id, String name, String description, UUID objectTypeId,
                                  String objectTypeName, String ownerName, String visibility,
                                  int version, long etag, long ontologyRevision,
                                  ObjectSetRequest query, List<UUID> columns, String perspective,
                                  Map<String, Object> viewConfig, Instant updatedAt,
                                  List<String> warnings) { }

    public record ObjectListRequest(String name, String description, UUID objectTypeId,
                                    UUID sourceExplorationId, String visibility, List<String> objectIds) { }

    public record ObjectListItemsRequest(List<String> objectIds) { }

    public record ObjectListView(UUID id, String name, String description, UUID objectTypeId,
                                 String objectTypeName, UUID sourceExplorationId, String ownerName,
                                 String visibility, long etag, long itemCount, Instant updatedAt) { }

    public record SelectionRequest(ObjectSetRequest query, List<String> objectIds) { }

    public record SelectionTokenView(String token, UUID objectTypeId, int objectCount,
                                     String queryFingerprint, Instant expiresAt) { }

    public record ExportRequest(ObjectSetRequest query, List<String> objectIds,
                                List<UUID> columns, String format) { }

    public record ExportJobView(UUID id, String status, String format, long rowCount,
                                String contentHash, String safeError, Instant createdAt,
                                Instant completedAt, Instant expiresAt, String downloadPath) { }

    public record BulkActionRequest(UUID actionId, String selectionToken,
                                    Map<String, Object> parameters) { }

    public record BulkActionJobView(UUID id, UUID actionId, String status, int totalCount,
                                    int succeededCount, int failedCount, int skippedCount,
                                    Instant createdAt, Instant completedAt) { }

    public record CompareRequest(UUID objectTypeId, List<String> objectIds) { }

    public record CompareResult(List<ObjectDetail> objects, List<UUID> differingProperties,
                                List<UUID> commonProperties) { }
}
