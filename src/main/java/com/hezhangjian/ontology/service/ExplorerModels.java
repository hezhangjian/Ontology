package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
                                       List<PropertyDefinition> properties) { }

    public record SortClause(UUID propertyId, String direction) { }

    public record ObjectSetRequest(UUID objectTypeId, Map<String, Object> where, List<SortClause> sort,
                                   Integer pageSize, String cursor, List<UUID> columns) { }

    public record ObjectSummary(String objectId, String title, String objectTypeApiName,
                                UUID objectTypeId,
                                Map<String, Object> properties, List<UUID> redactedFields,
                                String quality, Instant updatedAt) { }

    public record ObjectSetPage(UUID objectTypeId, String objectTypeName,
                                long visibleCount, boolean countLowerBound, List<ObjectSummary> items,
                                String nextCursor, String queryFingerprint, Instant indexUpdatedAt,
                                List<PropertyDefinition> properties) { }

    public record FacetRequest(ObjectSetRequest query, List<UUID> propertyIds) { }

    public record FacetBucket(Object value, long count) { }

    public record FacetResult(UUID propertyId, String displayName, List<FacetBucket> buckets) { }

    public record AggregationBucket(Object value, long count, double metric) { }

    public record AggregateRequest(ObjectSetRequest query, List<UUID> dimensionPropertyIds,
                                   UUID measurePropertyId, UUID divisorPropertyId,
                                   String aggregation) { }

    public record AggregateResponse(UUID objectTypeId,
                                    String queryFingerprint, Instant calculatedAt,
                                    List<AggregationBucket> buckets) { }

    public record SearchRequest(String query, String mode, String tab, Integer size) { }

    public record SearchResponse(List<ObjectSummary> objects, List<ObjectTypeDefinition> objectTypes,
                                 long visibleObjectCount, Instant indexUpdatedAt) { }

    public record ExplorerHome(List<ObjectTypeDefinition> objectTypes, Map<UUID, Long> objectCounts,
                               String searchStatus, Instant indexUpdatedAt) { }

    public record ObjectDetail(String objectId, String title, ObjectTypeDefinition objectType,
                               @JsonIgnore String etag,
                               Map<String, Object> properties, List<UUID> redactedFields,
                               String quality, Instant updatedAt) { }

    public record LinkRequest(String direction, List<UUID> linkTypeIds, Integer pageSize, String cursor) { }

    public record ObjectLink(String relationId, UUID linkTypeId, String linkTypeName, String direction,
                             String targetObjectId, UUID targetObjectTypeId, String targetTitle,
                             Map<String, Object> edgeProperties) { }

    public record LinkPage(List<ObjectLink> items, String nextCursor, long visibleCount) { }

    public record InstanceReference(String type, String id, String title) { }

    public record RelationInstance(String id, String type, InstanceReference source,
                                   InstanceReference target, Map<String, Object> properties) { }

    public record RelationInstancePage(long total, List<RelationInstance> items,
                                       String nextCursor) { }

    public record InstanceKey(String type, String id) { }

    public record RelationInstanceQueryRequest(InstanceKey source, String type,
                                               String direction, Integer pageSize) { }

    public record InterfaceQueryRequest(Integer pageSize) { }

    public record InterfaceObject(String objectId, String title, String objectTypeApiName,
                                  UUID objectTypeId, Map<String, Object> slots) { }

    public record InterfaceQueryPage(UUID interfaceId,
                                     List<InterfaceObject> items, boolean truncated) { }

    public record Capability(UUID id, String kind, String displayName, String apiName,
                             boolean executable, boolean previewRequired) { }

    public record CapabilityResponse(List<Capability> actions, List<Capability> functions,
                                     List<String> openTo) { }

    public record ActivityItem(String kind, String status, String summary, String actor,
                               String correlationId, Instant occurredAt) { }

    public record ProvenanceView(String objectId, String primaryPipeline, @JsonIgnore Integer pipelineVersion,
                                 String projectionStatus, String sourceAsset,
                                 String indexStatus, List<Map<String, Object>> fieldLineage) { }

}
