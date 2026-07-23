package com.hezhangjian.ontology.contracts.projection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IndexRebuildCommand(
        UUID rebuildId,
        UUID ontologyId,
        Instant requestedAt,
        String requestedBy,
        String correlationId) {
}
