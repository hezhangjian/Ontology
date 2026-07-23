package com.hezhangjian.ontology.core.explorer;

import static com.hezhangjian.ontology.core.explorer.ExplorerModels.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import com.hezhangjian.ontology.core.security.ActorIdentity;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
public class ExplorerController {
    private final ExplorerService service;

    public ExplorerController(ExplorerService service) {
        this.service = service;
    }

    @GetMapping("/explorer/home")
    ExplorerHome home(Authentication authentication) { return service.home(actor(authentication)); }

    @PostMapping("/search/objects")
    SearchResponse search(@RequestBody SearchRequest request, Authentication authentication) {
        return service.search(request, actor(authentication));
    }

    @PostMapping("/object-sets/query")
    ObjectSetPage query(@RequestBody ObjectSetRequest request, Authentication authentication) {
        return service.query(request, actor(authentication));
    }

    @PostMapping("/object-sets/facets")
    List<FacetResult> facets(@RequestBody FacetRequest request, Authentication authentication) {
        return service.facets(request, actor(authentication));
    }

    @PostMapping("/object-sets/aggregate")
    AggregateResponse aggregate(@RequestBody AggregateRequest request, Authentication authentication) {
        return service.aggregate(request, actor(authentication));
    }

    @PostMapping("/object-sets/search-around")
    LinkPage searchAround(@RequestBody SearchAroundRequest request, Authentication authentication) {
        return service.links(request.objectTypeId(), request.objectId(),
                new LinkRequest("BOTH", request.linkTypeIds(), request.pageSize(), null), actor(authentication));
    }

    @PostMapping("/object-sets/compare")
    CompareResult compare(@RequestBody CompareRequest request, Authentication authentication) {
        return service.compare(request, actor(authentication));
    }

    @PostMapping("/object-sets/selection-tokens")
    ResponseEntity<SelectionTokenView> selection(@RequestBody SelectionRequest request, Authentication authentication) {
        return ResponseEntity.status(201).body(service.selection(request, actor(authentication)));
    }

    @GetMapping("/objects/{objectTypeId}/{objectId}")
    ObjectDetail object(@PathVariable UUID objectTypeId, @PathVariable String objectId, Authentication authentication) {
        return service.object(objectTypeId, objectId, actor(authentication));
    }

    @GetMapping("/objects/{objectTypeId}/{objectId}/capabilities")
    CapabilityResponse capabilities(@PathVariable UUID objectTypeId, @PathVariable String objectId,
                                    Authentication authentication) {
        return service.capabilities(objectTypeId, objectId, actor(authentication));
    }

    @PostMapping("/objects/{objectTypeId}/{objectId}/links")
    LinkPage links(@PathVariable UUID objectTypeId, @PathVariable String objectId,
                   @RequestBody(required = false) LinkRequest request, Authentication authentication) {
        return service.links(objectTypeId, objectId, request, actor(authentication));
    }

    @GetMapping("/objects/{objectTypeId}/{objectId}/activity")
    List<ActivityItem> activity(@PathVariable UUID objectTypeId, @PathVariable String objectId,
                                Authentication authentication) {
        return service.activity(objectTypeId, objectId, actor(authentication));
    }

    @GetMapping("/objects/{objectTypeId}/{objectId}/provenance")
    ProvenanceView provenance(@PathVariable UUID objectTypeId, @PathVariable String objectId,
                              Authentication authentication) {
        return service.provenance(objectTypeId, objectId, actor(authentication));
    }

    @GetMapping("/explorations")
    List<ExplorationView> explorations(Authentication authentication) { return service.explorations(actor(authentication)); }

    @PostMapping("/explorations")
    ResponseEntity<ExplorationView> createExploration(@RequestBody ExplorationRequest request,
                                                      Authentication authentication) {
        ExplorationView value = service.createExploration(request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/explorations/" + value.id())).body(value);
    }

    @GetMapping("/explorations/{id}")
    ExplorationView exploration(@PathVariable UUID id, Authentication authentication) {
        return service.exploration(id, actor(authentication));
    }

    @PutMapping("/explorations/{id}")
    ExplorationView updateExploration(@PathVariable UUID id, @RequestHeader("If-Match") long ifMatch,
                                      @RequestBody ExplorationRequest request, Authentication authentication) {
        return service.updateExploration(id, ifMatch, request, actor(authentication));
    }

    @DeleteMapping("/explorations/{id}")
    ResponseEntity<Void> deleteExploration(@PathVariable UUID id, Authentication authentication) {
        service.deleteExploration(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/explorations/{id}/share")
    ExplorationView shareExploration(@PathVariable UUID id, Authentication authentication) {
        return service.shareExploration(id, actor(authentication));
    }

    @GetMapping("/object-lists")
    List<ObjectListView> lists(Authentication authentication) { return service.lists(actor(authentication)); }

    @PostMapping("/object-lists")
    ResponseEntity<ObjectListView> createList(@RequestBody ObjectListRequest request, Authentication authentication) {
        ObjectListView value = service.createList(request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/object-lists/" + value.id())).body(value);
    }

    @GetMapping("/object-lists/{id}")
    ObjectListView list(@PathVariable UUID id, Authentication authentication) {
        return service.list(id, actor(authentication));
    }

    @PostMapping("/object-lists/{id}/items")
    ObjectListView addListItems(@PathVariable UUID id, @RequestBody ObjectListItemsRequest request,
                                Authentication authentication) {
        return service.addListItems(id, request, actor(authentication));
    }

    @DeleteMapping("/object-lists/{id}/items")
    ObjectListView removeListItems(@PathVariable UUID id, @RequestBody ObjectListItemsRequest request,
                                   Authentication authentication) {
        return service.removeListItems(id, request, actor(authentication));
    }

    @PostMapping("/export-jobs")
    ResponseEntity<ExportJobView> export(@RequestBody ExportRequest request, Authentication authentication) {
        return ResponseEntity.accepted().body(service.export(request, actor(authentication)));
    }

    @GetMapping("/export-jobs/{id}")
    ExportJobView exportJob(@PathVariable UUID id, Authentication authentication) {
        return service.exportJob(id, actor(authentication));
    }

    @PostMapping("/export-jobs/{id}/cancel")
    ExportJobView cancelExport(@PathVariable UUID id, Authentication authentication) {
        return service.cancelExport(id, actor(authentication));
    }

    @GetMapping("/export-jobs/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable UUID id, Authentication authentication) {
        byte[] content = service.download(id, actor(authentication));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment().filename("ontology-export-" + id + ".csv").build());
        return ResponseEntity.ok().headers(headers).body(content);
    }

    @PostMapping("/bulk-action-jobs")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<BulkActionJobView> bulkAction(@RequestBody BulkActionRequest request, Authentication authentication) {
        return ResponseEntity.accepted().body(service.bulkAction(request, actor(authentication)));
    }

    @GetMapping("/bulk-action-jobs/{id}")
    BulkActionJobView bulkActionJob(@PathVariable UUID id, Authentication authentication) {
        return service.bulkActionJob(id, actor(authentication));
    }

    @PostMapping("/bulk-action-jobs/{id}/retry-failed")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    BulkActionJobView retryBulk(@PathVariable UUID id, Authentication authentication) {
        return service.retryBulk(id, actor(authentication));
    }

    @PostMapping("/bulk-action-jobs/{id}/cancel")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    BulkActionJobView cancelBulk(@PathVariable UUID id, Authentication authentication) {
        return service.cancelBulk(id, actor(authentication));
    }

    private Actor actor(Authentication authentication) {
        ActorIdentity identity = ActorIdentity.from(authentication);
        return new Actor(identity.id(), identity.name(), identity.roles());
    }

    public record SearchAroundRequest(UUID objectTypeId, String objectId, List<UUID> linkTypeIds,
                                      Integer pageSize) { }
}
