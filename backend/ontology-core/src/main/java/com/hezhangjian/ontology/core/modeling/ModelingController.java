package com.hezhangjian.ontology.core.modeling;

import static com.hezhangjian.ontology.core.modeling.ModelingModels.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.hezhangjian.ontology.core.security.ActorIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/modeling")
@PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
public class ModelingController {
    private final ModelingService service;
    private final OntologyCatalogService catalogs;

    public ModelingController(ModelingService service, OntologyCatalogService catalogs) {
        this.service = service;
        this.catalogs = catalogs;
    }

    @GetMapping("/summary")
    ModelingSummary summary(@RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.summary(catalogs.resolve(ontology)); }

    @GetMapping("/search")
    List<SearchResult> search(@RequestParam(defaultValue = "") String query, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.search(catalogs.resolve(ontology), query); }

    @GetMapping("/object-types")
    List<ResourceView> objectTypes(@RequestParam(required = false) String search, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.list(catalogs.resolve(ontology), ResourceKind.OBJECT_TYPE, search); }

    @PostMapping("/object-types")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createObjectType(@RequestBody ResourceDraftRequest request, Authentication authentication, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) {
        return created(service.create(catalogs.resolve(ontology), ResourceKind.OBJECT_TYPE, request, actor(authentication)));
    }

    @GetMapping("/object-types/{id}")
    ResourceView objectType(@PathVariable UUID id) { return requireKind(id, ResourceKind.OBJECT_TYPE); }

    @DeleteMapping("/object-types/{id}")
    ResponseEntity<Void> deleteObjectType(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.OBJECT_TYPE);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/object-types/{id}/drafts")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createObjectDraft(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                                    @RequestBody ResourceDraftRequest request, Authentication authentication) {
        ResourceView resource = service.createObjectDraft(id, request, parseEtag(ifMatch), actor(authentication));
        return ResponseEntity.ok().eTag(Long.toString(resource.etag())).body(resource);
    }

    @GetMapping("/properties")
    List<PropertyView> properties(@RequestParam(required = false) UUID objectTypeId, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.propertiesForOntology(catalogs.resolve(ontology), objectTypeId); }

    @GetMapping("/properties/{id}")
    PropertyView property(@PathVariable UUID id) {
        return service.properties(null).stream().filter(property -> property.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "属性不存在"));
    }

    @GetMapping("/link-types")
    List<ResourceView> linkTypes(@RequestParam(required = false) String search, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.list(catalogs.resolve(ontology), ResourceKind.LINK_TYPE, search); }

    @PostMapping("/link-types")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createLinkType(@RequestBody ResourceDraftRequest request, Authentication authentication, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) {
        return created(service.create(catalogs.resolve(ontology), ResourceKind.LINK_TYPE, request, actor(authentication)));
    }

    @GetMapping("/link-types/{id}")
    ResourceView linkType(@PathVariable UUID id) { return requireKind(id, ResourceKind.LINK_TYPE); }

    @DeleteMapping("/link-types/{id}")
    ResponseEntity<Void> deleteLinkType(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.LINK_TYPE);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/interfaces")
    List<ResourceView> interfaces(@RequestParam(required = false) String search, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.list(catalogs.resolve(ontology), ResourceKind.INTERFACE, search); }

    @PostMapping("/interfaces")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createInterface(@RequestBody ResourceDraftRequest request, Authentication authentication, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) {
        return created(service.create(catalogs.resolve(ontology), ResourceKind.INTERFACE, request, actor(authentication)));
    }

    @GetMapping("/interfaces/{id}")
    ResourceView interfaceType(@PathVariable UUID id) { return requireKind(id, ResourceKind.INTERFACE); }

    @DeleteMapping("/interfaces/{id}")
    ResponseEntity<Void> deleteInterface(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.INTERFACE);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/actions")
    List<ResourceView> actions(@RequestParam(required = false) String search, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.list(catalogs.resolve(ontology), ResourceKind.ACTION, search); }

    @PostMapping("/actions")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createAction(@RequestBody ResourceDraftRequest request, Authentication authentication, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) {
        return created(service.create(catalogs.resolve(ontology), ResourceKind.ACTION, request, actor(authentication)));
    }

    @GetMapping("/actions/{id}")
    ResourceView action(@PathVariable UUID id) { return requireKind(id, ResourceKind.ACTION); }

    @DeleteMapping("/actions/{id}")
    ResponseEntity<Void> deleteAction(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.ACTION);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/actions/{id}/preview")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ActionPreview previewAction(@PathVariable UUID id, @RequestBody(required = false) ActionPreviewRequest request,
                                Authentication authentication) {
        return service.previewAction(id, request, actor(authentication));
    }

    @PostMapping("/actions/{id}/execute")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ActionExecution> executeAction(@PathVariable UUID id,
                                                   @RequestBody ActionExecuteRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.accepted().body(service.executeAction(id, request, actor(authentication)));
    }

    @GetMapping("/action-executions/{id}")
    ActionExecution actionExecution(@PathVariable UUID id, Authentication authentication) {
        return service.actionExecution(id, actor(authentication));
    }

    @GetMapping("/functions")
    List<ResourceView> functions(@RequestParam(required = false) String search, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.list(catalogs.resolve(ontology), ResourceKind.FUNCTION, search); }

    @PostMapping("/functions")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createFunction(@RequestBody ResourceDraftRequest request, Authentication authentication, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) {
        return created(service.create(catalogs.resolve(ontology), ResourceKind.FUNCTION, request, actor(authentication)));
    }

    @GetMapping("/functions/{id}")
    ResourceView function(@PathVariable UUID id) { return requireKind(id, ResourceKind.FUNCTION); }

    @PostMapping("/functions/{id}/draft")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createFunctionDraft(@PathVariable UUID id,
                                                      @RequestBody ResourceDraftRequest request,
                                                      @RequestHeader("If-Match") long expectedEtag,
                                                      Authentication authentication) {
        return created(service.createFunctionDraft(id, request, expectedEtag, actor(authentication)));
    }

    @DeleteMapping("/functions/{id}")
    ResponseEntity<Void> deleteFunction(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.FUNCTION);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/functions/{id}/test")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    FunctionTestResult testFunction(@PathVariable UUID id, @RequestBody(required = false) FunctionTestRequest request) {
        return service.testFunction(id, request);
    }

    @GetMapping("/proposals")
    List<ProposalView> proposals(@RequestHeader(value = "X-Ontology-Id", required = false) String ontology) { return service.proposals(catalogs.resolve(ontology)); }

    @PostMapping("/proposals")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ProposalView> createProposal(@RequestBody ProposalRequest request, Authentication authentication, @RequestHeader(value = "X-Ontology-Id", required = false) String ontology) {
        ProposalView proposal = service.createProposal(catalogs.resolve(ontology), request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/modeling/proposals/" + proposal.id())).body(proposal);
    }

    @GetMapping("/proposals/{id}")
    ProposalView proposal(@PathVariable UUID id) { return service.proposal(id); }

    @PostMapping("/proposals/{id}/validate")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ProposalView validate(@PathVariable UUID id, Authentication authentication) { return service.validate(id, actor(authentication)); }

    @PostMapping("/proposals/{id}/submit")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ProposalView submit(@PathVariable UUID id, Authentication authentication) { return service.submit(id, actor(authentication)); }

    @PostMapping("/proposals/{id}/reviews")
    @PreAuthorize("hasRole('Admin')")
    ProposalView review(@PathVariable UUID id, @RequestBody ReviewRequest request, Authentication authentication) {
        return service.review(id, request, actor(authentication));
    }

    @PostMapping("/proposals/{id}/publish")
    @PreAuthorize("hasRole('Admin')")
    ResponseEntity<DeploymentView> publish(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(service.publish(id, actor(authentication)));
    }

    @PostMapping("/proposals/{id}/retry")
    @PreAuthorize("hasRole('Admin')")
    ResponseEntity<DeploymentView> retry(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(service.retry(id, actor(authentication)));
    }

    @PostMapping("/proposals/{id}/close")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ProposalView close(@PathVariable UUID id, Authentication authentication) { return service.close(id, actor(authentication)); }

    @GetMapping("/health")
    List<HealthIssue> health() { return service.health(); }

    @GetMapping("/history")
    List<HistoryEntry> history() { return service.history(); }

    @GetMapping("/deployments/{id}")
    DeploymentView deployment(@PathVariable UUID id) { return service.deployment(id); }

    private ResourceView requireKind(UUID id, ResourceKind kind) {
        ResourceView resource = service.get(id);
        if (resource.kind() != kind) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在");
        return resource;
    }

    private ResponseEntity<ResourceView> created(ResourceView resource) {
        return ResponseEntity.created(URI.create("/v1/modeling/" + resource.kind().name().toLowerCase() + "/" + resource.id()))
                .eTag(Long.toString(resource.etag())).body(resource);
    }

    private long parseEtag(String value) {
        try { return Long.parseLong(value.replace("\"", "")); }
        catch (NumberFormatException failure) { throw new com.hezhangjian.ontology.core.connections.ConnectionProblem("ONTOLOGY_VERSION_CONFLICT", "If-Match 必须是当前资源版本"); }
    }

    private Actor actor(Authentication authentication) {
        ActorIdentity identity = ActorIdentity.from(authentication);
        return new Actor(identity.id(), identity.name(), identity.admin());
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "unknown";
    }
}
