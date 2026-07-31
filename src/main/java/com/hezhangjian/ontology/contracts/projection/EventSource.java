package com.hezhangjian.ontology.contracts.projection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record EventSource(
        String dataSourceId,
        String assetId,
        String pipelineRunId) {
}
