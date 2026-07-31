package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.ExplorerApi;
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
import com.hezhangjian.ontology.service.ExplorerApiService;
import com.hezhangjian.ontology.service.ExplorerApiService.VersionedObject;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class ExplorerController implements ExplorerApi {
    private final ExplorerApiService explorerService;

    @Override
    public ResponseEntity<List<ActivityItem>> listObjectActivity(
            String objectId, UUID objectTypeId, String ontologyId) {
        return ResponseEntity.ok(
                explorerService.activity(ontologyId, objectTypeId, objectId));
    }

    @Override
    public ResponseEntity<AggregateResponse> aggregate(
            String ontologyId, AggregateRequest aggregateRequest) {
        return ResponseEntity.ok(explorerService.aggregate(ontologyId, aggregateRequest));
    }

    @Override
    public ResponseEntity<ObjectDetail> getObjectDetail(
            String objectId, UUID objectTypeId, String ontologyId) {
        VersionedObject<ObjectDetail> value =
                explorerService.object(ontologyId, objectTypeId, objectId);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<CapabilityResponse> getObjectCapabilities(
            String objectId, UUID objectTypeId, String ontologyId) {
        return ResponseEntity.ok(
                explorerService.capabilities(ontologyId, objectTypeId, objectId));
    }

    @Override
    public ResponseEntity<List<FacetResult>> facets(
            String ontologyId, FacetRequest facetRequest) {
        return ResponseEntity.ok(explorerService.facets(ontologyId, facetRequest));
    }

    @Override
    public ResponseEntity<ExplorerHome> getExplorerHome(String ontologyId) {
        return ResponseEntity.ok(explorerService.home(ontologyId));
    }

    @Override
    public ResponseEntity<InterfaceQueryPage> interfaceQuery(
            UUID interfaceId,
            String ontologyId,
            InterfaceQueryRequest interfaceQueryRequest) {
        return ResponseEntity.ok(
                explorerService.interfaceQuery(ontologyId, interfaceId, interfaceQueryRequest));
    }

    @Override
    public ResponseEntity<LinkPage> links(
            String objectId,
            UUID objectTypeId,
            String ontologyId,
            LinkRequest linkRequest) {
        return ResponseEntity.ok(
                explorerService.links(ontologyId, objectTypeId, objectId, linkRequest));
    }

    @Override
    public ResponseEntity<ProvenanceView> getObjectProvenance(
            String objectId, UUID objectTypeId, String ontologyId) {
        return ResponseEntity.ok(
                explorerService.provenance(ontologyId, objectTypeId, objectId));
    }

    @Override
    public ResponseEntity<ObjectSetPage> queryObjectSet(
            String ontologyId, ObjectSetRequest objectSetRequest) {
        return ResponseEntity.ok(explorerService.query(ontologyId, objectSetRequest));
    }

    @Override
    public ResponseEntity<SearchResponse> searchObjects(
            String ontologyId, SearchRequest searchRequest) {
        return ResponseEntity.ok(explorerService.search(ontologyId, searchRequest));
    }

    @Override
    public ResponseEntity<LinkPage> searchAround(
            String ontologyId, SearchAroundRequest searchAroundRequest) {
        return ResponseEntity.ok(
                explorerService.searchAround(ontologyId, searchAroundRequest));
    }

}
