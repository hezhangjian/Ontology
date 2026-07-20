package com.hezhangjian.ontology.contracts.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OntologyEventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        long ontologyRevision,
        Instant occurredAt,
        String producer,
        String correlationId,
        String traceId,
        String flinkJobId,
        String objectType,
        String objectId,
        Long objectVersion,
        String relationType,
        String relationId,
        Long relationVersion,
        String sourceObjectType,
        String sourceObjectId,
        String targetObjectType,
        String targetObjectId,
        JsonNode payload,
        EventSource source) {
}
