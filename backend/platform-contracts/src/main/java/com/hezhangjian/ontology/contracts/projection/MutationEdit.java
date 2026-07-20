package com.hezhangjian.ontology.contracts.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record MutationEdit(
        String operation,
        String objectTypeId,
        String objectId,
        Long expectedVersion,
        String relationTypeId,
        String relationId,
        String sourceObjectTypeId,
        String sourceObjectId,
        String targetObjectTypeId,
        String targetObjectId,
        JsonNode properties) {
}
