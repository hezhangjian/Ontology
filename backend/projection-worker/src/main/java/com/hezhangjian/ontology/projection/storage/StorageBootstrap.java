package com.hezhangjian.ontology.projection.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
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
