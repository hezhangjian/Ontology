package com.hezhangjian.ontology.service;

import com.hezhangjian.ontology.contracts.projection.IndexRebuildCommand;
import com.hezhangjian.ontology.instance.ObjectInstanceAuthorityReader;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient.GraphObject;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import com.hezhangjian.ontology.security.WorkspaceContext;
import com.hezhangjian.ontology.model.GraphSchemaRebuildResult;
import com.hezhangjian.ontology.model.RebuildResult;
import com.hezhangjian.ontology.projection.IndexRebuildProcessor;
import com.hezhangjian.ontology.projection.storage.HugeGraphProjectionClient;
import com.hezhangjian.ontology.projection.storage.OpenSearchProjectionClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class ProjectionAdminService {
    private final OntologyLookupService catalogs;
    private final HugeGraphProjectionClient graph;
    private final IndexRebuildProcessor rebuilds;
    private final ObjectInstanceAuthorityReader objects;
    private final SqlClientRepository jdbc;
    private final OpenSearchProjectionClient search;

    public RebuildResult rebuildIndex(String ontologyApiName) {
        return inOntology(ontologyApiName, () -> {
            UUID commandId = UUID.randomUUID();
            OpenSearchProjectionClient.RebuildResult result = rebuilds.rebuild(
                    new IndexRebuildCommand(
                            commandId,
                            WorkspaceContext.id(),
                            Instant.now(),
                            "local",
                            "index-rebuild:" + commandId));
            return new RebuildResult()
                    .index(result.index())
                    .objectCount(result.objectCount());
        });
    }

    public GraphSchemaRebuildResult rebuildGraphSchema(String ontologyApiName) {
        return inOntology(ontologyApiName, () -> {
            HugeGraphProjectionClient.GraphSchemaRebuildResult result =
                    graph.rebuildSchemaWithoutLegacyVersions(
                            authoritativeObjects(), search.currentRelations());
            return new GraphSchemaRebuildResult()
                    .objectCount(result.objectCount())
                    .relationCount(result.relationCount())
                    .technicalFields(result.technicalFields());
        });
    }

    private List<GraphObject> authoritativeObjects() {
        List<GraphObject> result = new ArrayList<>();
        jdbc.sql("""
                SELECT ontology_id,object_type_physical_key
                FROM control.object_instance_table_registry
                WHERE status='READY'
                ORDER BY ontology_id,object_type_physical_key
                """).query((row, number) -> new ObjectTypeBinding(
                        row.getObject("ontology_id", UUID.class),
                        row.getString("object_type_physical_key")))
                .list()
                .forEach(type -> objects.list(type.ontologyId(), type.physicalKey())
                        .forEach(object -> result.add(new GraphObject(
                                null,
                                type.ontologyId().toString(),
                                type.physicalKey(),
                                object.id(),
                                object.version(),
                                object.payload(),
                                null,
                                Instant.now().toString()))));
        return List.copyOf(result);
    }

    private <T> T inOntology(String apiName, Supplier<T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, work);
    }

    private record ObjectTypeBinding(UUID ontologyId, String physicalKey) {}
}
