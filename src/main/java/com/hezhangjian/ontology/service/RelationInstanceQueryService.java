package com.hezhangjian.ontology.service;

import com.hezhangjian.ontology.service.ExplorerModels.Actor;
import com.hezhangjian.ontology.service.ExplorerModels.InstanceKey;
import com.hezhangjian.ontology.service.ExplorerModels.RelationInstanceQueryRequest;
import com.hezhangjian.ontology.security.WorkspaceContext;
import com.hezhangjian.ontology.model.InstanceReference;
import com.hezhangjian.ontology.model.RelationInstance;
import com.hezhangjian.ontology.model.RelationInstancePage;
import com.hezhangjian.ontology.model.RelationInstanceQueryReq;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class RelationInstanceQueryService {
    private final OntologyLookupService catalogs;
    private final ExplorerService explorer;

    public RelationInstanceQueryService(
            OntologyLookupService catalogs, ExplorerService explorer) {
        this.catalogs = catalogs;
        this.explorer = explorer;
    }

    public RelationInstancePage listRelations(
            String ontologyApiName,
            String objectTypeApiName,
            String objectId,
            String linkTypeApiName,
            String direction,
            Integer pageSize) {
        return inOntology(
                ontologyApiName,
                () -> toModel(explorer.relationInstances(
                        objectTypeApiName,
                        objectId,
                        linkTypeApiName,
                        direction,
                        pageSize,
                        actor())));
    }

    public RelationInstancePage queryRelations(
            String ontologyApiName, RelationInstanceQueryReq request) {
        return inOntology(ontologyApiName, () -> {
            RelationInstanceQueryRequest query = new RelationInstanceQueryRequest(
                    new InstanceKey(
                            request.getSource().getType(), request.getSource().getId()),
                    request.getType(),
                    request.getDirection().getValue(),
                    request.getPageSize());
            return toModel(explorer.relationInstances(query, actor()));
        });
    }

    private <T> T inOntology(String apiName, Supplier<T> work) {
        UUID internalId = catalogs.resolve(apiName);
        catalogs.get(internalId);
        return WorkspaceContext.call(internalId, work);
    }

    private Actor actor() {
        return new Actor("local", "Local", List.of("Admin", "Builder", "Viewer"));
    }

    private RelationInstancePage toModel(
            com.hezhangjian.ontology.service.ExplorerModels.RelationInstancePage source) {
        List<RelationInstance> items = source.items().stream()
                .map(item -> new RelationInstance()
                        .id(item.id())
                        .type(item.type())
                        .source(reference(item.source()))
                        .target(reference(item.target()))
                        .properties(item.properties()))
                .toList();
        return new RelationInstancePage()
                .total(source.total())
                .items(items)
                .nextCursor(source.nextCursor());
    }

    private InstanceReference reference(
            com.hezhangjian.ontology.service.ExplorerModels.InstanceReference source) {
        return new InstanceReference()
                .type(source.type())
                .id(source.id())
                .title(source.title());
    }
}
