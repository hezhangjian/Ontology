package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.security.WorkspaceContext;
import com.hezhangjian.ontology.model.ActivityItem;
import com.hezhangjian.ontology.model.AggregateRequest;
import com.hezhangjian.ontology.model.AggregateResponse;
import com.hezhangjian.ontology.model.CapabilityResponse;
import com.hezhangjian.ontology.model.ExplorerHome;
import com.hezhangjian.ontology.model.FacetRequest;
import com.hezhangjian.ontology.model.FacetResult;
import com.hezhangjian.ontology.model.InterfaceQueryPage;
import com.hezhangjian.ontology.model.InterfaceQueryRequest;
import com.hezhangjian.ontology.model.LinkPage;
import com.hezhangjian.ontology.model.LinkRequest;
import com.hezhangjian.ontology.model.ObjectDetail;
import com.hezhangjian.ontology.model.ObjectSetPage;
import com.hezhangjian.ontology.model.ObjectSetRequest;
import com.hezhangjian.ontology.model.ProvenanceView;
import com.hezhangjian.ontology.model.SearchAroundRequest;
import com.hezhangjian.ontology.model.SearchRequest;
import com.hezhangjian.ontology.model.SearchResponse;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class ExplorerApiService {
    private final OntologyLookupService catalogs;
    private final ObjectMapper objectMapper;
    private final ExplorerService explorer;

    public ExplorerHome home(String ontologyApiName) {
        return inOntology(ontologyApiName, () ->
                convert(explorer.home(actor()), ExplorerHome.class));
    }

    public SearchResponse search(String ontologyApiName, SearchRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.search(request(request, ExplorerModels.SearchRequest.class), actor()),
                SearchResponse.class));
    }

    public ObjectSetPage query(String ontologyApiName, ObjectSetRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.query(request(request, ExplorerModels.ObjectSetRequest.class), actor()),
                ObjectSetPage.class));
    }

    public List<FacetResult> facets(String ontologyApiName, FacetRequest request) {
        return inOntology(ontologyApiName, () -> convertList(
                explorer.facets(request(request, ExplorerModels.FacetRequest.class), actor()),
                FacetResult.class));
    }

    public AggregateResponse aggregate(
            String ontologyApiName, AggregateRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.aggregate(
                        request(request, ExplorerModels.AggregateRequest.class), actor()),
                AggregateResponse.class));
    }

    public LinkPage searchAround(
            String ontologyApiName, SearchAroundRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.links(
                        request.getObjectTypeId(),
                        request.getObjectId(),
                        new ExplorerModels.LinkRequest(
                                "BOTH",
                                request.getLinkTypeIds(),
                                request.getPageSize(),
                                null),
                        actor()),
                LinkPage.class));
    }

    public InterfaceQueryPage interfaceQuery(
            String ontologyApiName, UUID interfaceId, InterfaceQueryRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.interfaceQuery(
                        interfaceId,
                        request(request, ExplorerModels.InterfaceQueryRequest.class),
                        actor()),
                InterfaceQueryPage.class));
    }

    public VersionedObject<ObjectDetail> object(
            String ontologyApiName, UUID objectTypeId, String objectId) {
        return inOntology(ontologyApiName, () -> {
            ExplorerModels.ObjectDetail value =
                    explorer.object(objectTypeId, objectId, actor());
            return new VersionedObject<>(
                    convert(value, ObjectDetail.class), value.etag());
        });
    }

    public CapabilityResponse capabilities(
            String ontologyApiName, UUID objectTypeId, String objectId) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.capabilities(objectTypeId, objectId, actor()),
                CapabilityResponse.class));
    }

    public LinkPage links(
            String ontologyApiName,
            UUID objectTypeId,
            String objectId,
            LinkRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.links(
                        objectTypeId,
                        objectId,
                        request(request, ExplorerModels.LinkRequest.class),
                        actor()),
                LinkPage.class));
    }

    public List<ActivityItem> activity(
            String ontologyApiName, UUID objectTypeId, String objectId) {
        return inOntology(ontologyApiName, () -> convertList(
                explorer.activity(objectTypeId, objectId, actor()), ActivityItem.class));
    }

    public ProvenanceView provenance(
            String ontologyApiName, UUID objectTypeId, String objectId) {
        return inOntology(ontologyApiName, () -> convert(
                explorer.provenance(objectTypeId, objectId, actor()),
                ProvenanceView.class));
    }

    private ExplorerModels.Actor actor() {
        return new ExplorerModels.Actor("local", "Local", List.of("Admin", "Builder", "Viewer"));
    }

    private <T> T inOntology(String apiName, Supplier<T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, work);
    }

    private <T> T request(Object source, Class<T> type) {
        return source == null ? null : objectMapper.convertValue(source, type);
    }

    private <T> T convert(Object source, Class<T> type) {
        return objectMapper.convertValue(source, type);
    }

    private <T> List<T> convertList(List<?> source, Class<T> type) {
        return source.stream().map(value -> convert(value, type)).toList();
    }

    public record VersionedObject<T>(T value, String etag) {}
}
