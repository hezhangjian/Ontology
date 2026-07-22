package com.hezhangjian.ontology.core.datasets;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DatasetModels {
    private DatasetModels() { }

    public record Field(String name, String type, boolean nullable, List<Object> samples) { }
    public record Dataset(UUID id, String name, String description, UUID pipelineId, String pipelineName,
                          List<Field> fields, long rowCount, String status, String ownerName,
                          Instant createdAt, Instant updatedAt) { }
    public record DatasetPage(List<Dataset> items, long total) { }
    public record Preview(List<String> columns, List<Map<String, Object>> rows, long total) { }
    public record MaterializeRequest(String name, String description) { }
    public record Dimension(String field, String label, String timeGrain) { }
    public record Metric(String operation, String field, String distinctField, String label) { }
    public record Filter(String field, String operator, List<String> values, String comparisonField) {
        public Filter(String field, String operator, List<String> values) {
            this(field, operator, values, null);
        }
    }
    public record QueryRequest(List<String> dimensions, List<Dimension> dimensionSpecs,
                               List<Metric> metrics, List<Filter> filters,
                               String orderBy, String orderDirection, Integer limit) { }
    public record QueryResult(List<String> dimensions, List<String> metrics, List<Map<String, Object>> rows,
                              long scannedRows) { }
    public record MappingPreview(String identityField, String titleField, long sourceRows,
                                 long objectCount, long emptyIdentityCount, long duplicateCount,
                                 long conflictCount, List<Map<String, Object>> samples) { }
}
