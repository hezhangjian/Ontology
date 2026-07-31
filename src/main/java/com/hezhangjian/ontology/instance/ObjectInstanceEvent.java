package com.hezhangjian.ontology.instance;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record ObjectInstanceEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID ontologyId,
        String ontologyApiName,
        UUID objectTypeId,
        String objectTypeApiName,
        String objectTypePhysicalKey,
        String objectId,
        long version,
        String title,
        JsonNode properties,
        Instant occurredAt,
        UUID correlationId,
        String source,
        boolean deleted) {}
