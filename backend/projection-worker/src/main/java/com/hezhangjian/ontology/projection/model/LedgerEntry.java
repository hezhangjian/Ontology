package com.hezhangjian.ontology.projection.model;

import java.util.UUID;

public record LedgerEntry(
        UUID eventId,
        String entityKey,
        long entityVersion,
        String status,
        int attempts,
        String graphElementId) {

    public boolean isTerminal() {
        return "DLQ".equals(status) || "PROJECTED".equals(status) || "STALE".equals(status);
    }
}
