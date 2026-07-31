package com.hezhangjian.ontology.projection;

import com.hezhangjian.ontology.contracts.projection.IndexRebuildCommand;
import com.hezhangjian.ontology.repo.ControlPlaneRepository;
import com.hezhangjian.ontology.repo.ControlPlaneRepository.RebuildJob;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import com.hezhangjian.ontology.instance.ObjectInstanceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient.RebuildResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IndexRebuildProcessor {
    private final ControlPlaneRepository repository;
    private final HugeGraphProjectionClient graph;
    private final OpenSearchProjectionClient search;
    private final ObjectInstanceRepository instances;
    private final ObjectInstanceProjectionProcessor projections;
    private final SqlClientRepository jdbc;
    private final ObjectMapper json;

    public IndexRebuildProcessor(
            ControlPlaneRepository repository,
            HugeGraphProjectionClient graph,
            OpenSearchProjectionClient search,
            ObjectInstanceRepository instances,
            ObjectInstanceProjectionProcessor projections,
            SqlClientRepository jdbc,
            ObjectMapper json) {
        this.repository = repository;
        this.graph = graph;
        this.search = search;
        this.instances = instances;
        this.projections = projections;
        this.jdbc = jdbc;
        this.json = json;
    }

    public RebuildResult rebuild(IndexRebuildCommand command) {
        RebuildJob job = repository.startRebuild(command);
        if (job.succeeded()) {
            return new RebuildResult(job.targetIndex(), job.objectCount());
        }
        try {
            long objectCount = rebuildObjects(command);
            search.rebuildRelations(graph.listRelations().stream()
                    .filter(relation -> command.ontologyId().toString().equals(relation.ontologyId()))
                    .toList());
            RebuildResult result = new RebuildResult("ontology-object-*", objectCount);
            repository.finishRebuild(command.rebuildId(), result.index(), result.objectCount());
            return result;
        } catch (RuntimeException exception) {
            repository.failRebuild(command.rebuildId(), exception.getMessage());
            throw exception;
        }
    }

    private long rebuildObjects(IndexRebuildCommand command) {
        List<TypeBinding> types = jdbc.sql("""
                SELECT object_type_api_name
                FROM control.object_instance_table_registry
                WHERE ontology_id=:ontology AND status='READY'
                ORDER BY object_type_api_name
                """).param("ontology", command.ontologyId())
                .query((row, number) -> new TypeBinding(
                        row.getString("object_type_api_name")))
                .list();
        String ontologyApiName = jdbc.sql("""
                SELECT api_name FROM control.ontologies WHERE id=:id
                """).param("id", command.ontologyId()).query(String.class).single();
        long count = 0;
        for (TypeBinding type : types) {
            var schema = instances.schema(command.ontologyId(), type.apiName());
            String cursor = null;
            do {
                var page = instances.list(schema, 200, cursor);
                for (var value : page.items()) {
                    ObjectNode payload = value.basePayload().isObject()
                            ? ((ObjectNode) value.basePayload()).deepCopy()
                            : json.createObjectNode();
                    if (value.overridePayload().isObject()) {
                        value.overridePayload().fields().forEachRemaining(
                                entry -> payload.set(entry.getKey(), entry.getValue()));
                    }
                    projections.repair(
                            ObjectInstanceProjectionProcessor.Target.OPENSEARCH,
                            new ObjectInstanceEvent(
                                    UUID.randomUUID(),
                                    "update",
                                    1,
                                    command.ontologyId(),
                                    ontologyApiName,
                                    schema.objectTypeId(),
                                    schema.objectTypeApiName(),
                                    schema.objectTypePhysicalKey(),
                                    value.id(),
                                    value.version(),
                                    value.title(),
                                    payload,
                                    Instant.now(),
                                    UUID.randomUUID(),
                                    "INDEX_REBUILD",
                                    false));
                    count++;
                }
                cursor = page.nextCursor();
            } while (cursor != null);
        }
        return count;
    }

    private record TypeBinding(String apiName) {}
}
