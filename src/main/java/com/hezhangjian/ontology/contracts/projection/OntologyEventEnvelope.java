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
        UUID ontologyId,
        Instant occurredAt,
        String producer,
        String correlationId,
        String traceId,
        String flinkJobId,
        String objectType,
        String objectId,
        String relationType,
        String relationId,
        String sourceObjectType,
        String sourceObjectId,
        String targetObjectType,
        String targetObjectId,
        JsonNode payload,
        EventSource source) {
}
