package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.IndexRebuildCommand;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository;
import com.hezhangjian.ontology.projection.control.ControlPlaneRepository.RebuildJob;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient.RebuildResult;
import com.hezhangjian.ontology.projection.validation.EventContractValidator;
import org.springframework.stereotype.Service;

@Service
public class IndexRebuildProcessor {
    private final ControlPlaneRepository repository;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;
    private final EventContractValidator validator;

    public IndexRebuildProcessor(
            ControlPlaneRepository repository,
            HugeGraphProjectionClient graph,
            OpenSearchProjectionClient search,
            EventContractValidator validator) {
        this.repository = repository;
        this.graph = graph;
        this.search = search;
        this.validator = validator;
    }

    public RebuildResult rebuild(IndexRebuildCommand command) {
        RebuildJob job = repository.startRebuild(command);
        if (job.succeeded()) {
            return new RebuildResult(job.targetIndex(), job.objectCount());
        }
        try {
            RebuildResult result = search.rebuildObjects(graph.listObjects(), validator::filterSearchable);
            repository.finishRebuild(command.rebuildId(), result.index(), result.objectCount());
            return result;
        } catch (RuntimeException exception) {
            repository.failRebuild(command.rebuildId(), exception.getMessage());
            throw exception;
        }
    }
}
