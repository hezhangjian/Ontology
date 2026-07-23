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
@RequestMapping("/v1/ontologies/{ontologyId}")
@PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
public class ModelingController {
    private final ModelingService service;
    private final OntologyCatalogService catalogs;

    public ModelingController(ModelingService service, OntologyCatalogService catalogs) {
        this.service = service;
        this.catalogs = catalogs;
    }

    @GetMapping("/summary")
    ModelingSummary summary() { return service.summary(catalogs.resolve(null)); }

    @GetMapping("/search")
    List<SearchResult> search(@RequestParam(defaultValue = "") String query) { return service.search(catalogs.resolve(null), query); }

    @GetMapping("/object-types")
    List<ResourceView> objectTypes(@RequestParam(required = false) String search) { return service.list(catalogs.resolve(null), ResourceKind.OBJECT_TYPE, search); }

    @PostMapping("/object-types")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createObjectType(@RequestBody ResourceDraftRequest request, Authentication authentication) {
        return created(service.create(catalogs.resolve(null), ResourceKind.OBJECT_TYPE, request, actor(authentication)));
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
    List<PropertyView> properties(@RequestParam(required = false) UUID objectTypeId) { return service.propertiesForOntology(catalogs.resolve(null), objectTypeId); }

    @GetMapping("/properties/{id}")
    PropertyView property(@PathVariable UUID id) {
        return service.properties(null).stream().filter(property -> property.id().equals(id)).findFirst()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "属性不存在"));
    }

    @GetMapping("/link-types")
    List<ResourceView> linkTypes(@RequestParam(required = false) String search) { return service.list(catalogs.resolve(null), ResourceKind.LINK_TYPE, search); }

    @PostMapping("/link-types")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createLinkType(@RequestBody ResourceDraftRequest request, Authentication authentication) {
        return created(service.create(catalogs.resolve(null), ResourceKind.LINK_TYPE, request, actor(authentication)));
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
    List<ResourceView> interfaces(@RequestParam(required = false) String search) { return service.list(catalogs.resolve(null), ResourceKind.INTERFACE, search); }

    @PostMapping("/interfaces")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createInterface(@RequestBody ResourceDraftRequest request, Authentication authentication) {
        return created(service.create(catalogs.resolve(null), ResourceKind.INTERFACE, request, actor(authentication)));
    }

    @GetMapping("/interfaces/{id}")
    ResourceView interfaceType(@PathVariable UUID id) { return requireKind(id, ResourceKind.INTERFACE); }

    @DeleteMapping("/interfaces/{id}")
    ResponseEntity<Void> deleteInterface(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.INTERFACE);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/action-types")
    List<ResourceView> actions(@RequestParam(required = false) String search) { return service.list(catalogs.resolve(null), ResourceKind.ACTION, search); }

    @PostMapping("/action-types")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createAction(@RequestBody ResourceDraftRequest request, Authentication authentication) {
        return created(service.create(catalogs.resolve(null), ResourceKind.ACTION, request, actor(authentication)));
    }

    @GetMapping("/action-types/{id}")
    ResourceView action(@PathVariable UUID id) { return requireKind(id, ResourceKind.ACTION); }

    @DeleteMapping("/action-types/{id}")
    ResponseEntity<Void> deleteAction(@PathVariable UUID id, Authentication authentication) {
        requireKind(id, ResourceKind.ACTION);
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/action-types/{id}/previews")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ActionPreview previewAction(@PathVariable UUID id, @RequestBody(required = false) ActionPreviewRequest request,
                                Authentication authentication) {
        return service.previewAction(id, request, actor(authentication));
    }

    @PostMapping("/action-types/{id}/executions")
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

    @GetMapping("/action-executions")
    List<ActionExecution> actionExecutions(@RequestParam(required = false) String status,
                                           Authentication authentication) {
        return service.actionExecutions(status, actor(authentication));
    }

    @PostMapping("/action-executions/{id}/reviews")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ActionExecution reviewAction(@PathVariable UUID id, @RequestBody ActionReviewRequest request,
                                 Authentication authentication) {
        return service.reviewAction(id, request, actor(authentication));
    }

    @GetMapping("/functions")
    List<ResourceView> functions(@RequestParam(required = false) String search) { return service.list(catalogs.resolve(null), ResourceKind.FUNCTION, search); }

    @PostMapping("/functions")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ResourceView> createFunction(@RequestBody ResourceDraftRequest request, Authentication authentication) {
        return created(service.create(catalogs.resolve(null), ResourceKind.FUNCTION, request, actor(authentication)));
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

    @PostMapping("/functions/{id}/executions")
    FunctionExecution testFunction(@PathVariable UUID id, @RequestBody(required = false) FunctionTestRequest request,
                                   Authentication authentication) {
        return service.testFunction(id, request, actor(authentication));
    }

    @GetMapping("/proposals")
    List<ProposalView> proposals() { return service.proposals(catalogs.resolve(null)); }

    @PostMapping("/proposals")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<ProposalView> createProposal(@RequestBody ProposalRequest request, Authentication authentication) {
        ProposalView proposal = service.createProposal(catalogs.resolve(null), request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/ontologies/" + catalogs.resolve(null) + "/proposals/" + proposal.id())).body(proposal);
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

    @PostMapping("/proposals/{id}/publication")
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

    @GetMapping("/revisions")
    List<HistoryEntry> history() { return service.history(); }

    @GetMapping("/deployments/{id}")
    DeploymentView deployment(@PathVariable UUID id) { return service.deployment(id); }

    private ResourceView requireKind(UUID id, ResourceKind kind) {
        ResourceView resource = service.get(id);
        if (resource.kind() != kind) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "本体资源不存在");
        return resource;
    }

    private ResponseEntity<ResourceView> created(ResourceView resource) {
        String segment = switch (resource.kind()) {
            case ACTION -> "action-types";
            case FUNCTION -> "functions";
            case INTERFACE -> "interfaces";
            case LINK_TYPE -> "link-types";
            case OBJECT_TYPE -> "object-types";
        };
        return ResponseEntity.created(URI.create("/v1/ontologies/" + catalogs.resolve(null) + "/" + segment + "/" + resource.id()))
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
