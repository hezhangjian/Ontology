package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.object-instances")
public record ObjectInstanceProperties(
        int importBatchSize,
        long outboxIntervalMs,
        long reconciliationIntervalMs) {
    public ObjectInstanceProperties {
        if (importBatchSize < 1 || importBatchSize > 10_000) {
            importBatchSize = 500;
        }
        if (outboxIntervalMs < 100) {
            outboxIntervalMs = 1_000;
        }
        if (reconciliationIntervalMs < 1_000) {
            reconciliationIntervalMs = 300_000;
        }
    }
}
