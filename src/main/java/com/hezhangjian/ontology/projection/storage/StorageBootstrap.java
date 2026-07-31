package com.hezhangjian.ontology.projection.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Ensures HugeGraph and OpenSearch schemas exist on startup.
 *
 * <p>Disabled in the {@code local} profile so the backend can run without the
 * full projection infrastructure (HugeGraph, OpenSearch). See
 * {@code application-local.yml}.</p>
 */
@Component
@ConditionalOnProperty(name = "ontology.storage.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
public class StorageBootstrap {
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;

    public StorageBootstrap(HugeGraphProjectionClient graph, OpenSearchProjectionClient search) {
        this.graph = graph;
        this.search = search;
    }

    @PostConstruct
    void initialize() {
        graph.ensureSchema();
        search.ensureIndexes();
    }
}
