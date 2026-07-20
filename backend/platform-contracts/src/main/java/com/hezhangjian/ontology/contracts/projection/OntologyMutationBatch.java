package com.hezhangjian.ontology.contracts.projection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OntologyMutationBatch(
        UUID batchId,
        long ontologyRevision,
        String actionTypeId,
        Long actionVersion,
        String previewTokenId,
        String idempotencyKey,
        String requestedBy,
        Instant occurredAt,
        String correlationId,
        List<MutationEdit> edits) {
}
