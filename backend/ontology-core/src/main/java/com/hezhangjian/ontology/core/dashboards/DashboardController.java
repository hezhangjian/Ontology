package com.hezhangjian.ontology.core.dashboards;

import static com.hezhangjian.ontology.core.dashboards.DashboardModels.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboards")
    List<DashboardSummary> list(@RequestParam(required = false) String keyword,
                                @RequestParam(required = false) String lifecycle,
                                @RequestParam(defaultValue = "false") boolean favorites,
                                Authentication authentication) {
        return service.list(keyword, lifecycle, favorites, actor(authentication));
    }

    @PostMapping("/dashboards")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<DashboardDetail> create(@RequestBody DashboardCreateRequest request, Authentication authentication) {
        DashboardDetail value = service.create(request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/dashboards/" + value.summary().id())).body(value);
    }

    @GetMapping("/dashboards/{id}")
    DashboardDetail get(@PathVariable UUID id, Authentication authentication) {
        return service.get(id, actor(authentication));
    }

    @PatchMapping("/dashboards/{id}")
    DashboardDetail patch(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                          @RequestBody DashboardPatchRequest request, Authentication authentication) {
        return service.patch(id, etag(ifMatch), request, actor(authentication));
    }

    @DeleteMapping("/dashboards/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.deleteEmptyDraft(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dashboards/{id}/copy")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<DashboardDetail> copy(@PathVariable UUID id, Authentication authentication) {
        DashboardDetail value = service.copy(id, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/dashboards/" + value.summary().id())).body(value);
    }

    @PostMapping("/dashboards/{id}/archive")
    DashboardDetail archive(@PathVariable UUID id, Authentication authentication) {
        return service.archive(id, actor(authentication));
    }

    @PostMapping("/dashboards/{id}/restore")
    DashboardDetail restore(@PathVariable UUID id, Authentication authentication) {
        return service.restore(id, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/draft")
    ResponseEntity<DashboardDraftView> draft(@PathVariable UUID id, Authentication authentication) {
        DashboardDraftView value = service.draft(id, actor(authentication));
        return ResponseEntity.ok().eTag(String.valueOf(value.etag())).body(value);
    }

    @PutMapping("/dashboards/{id}/draft")
    ResponseEntity<DashboardDraftView> putDraft(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                                @RequestBody DashboardDefinition request, Authentication authentication) {
        DashboardDraftView value = service.putDraft(id, etag(ifMatch), request, actor(authentication), true);
        return ResponseEntity.ok().eTag(String.valueOf(value.etag())).body(value);
    }

    @PostMapping("/dashboards/{id}/edit-lock")
    DashboardEditLock lock(@PathVariable UUID id, @RequestBody(required = false) EditLockRequest request,
                           Authentication authentication) {
        return service.acquireLock(id, request != null && Boolean.TRUE.equals(request.force()), actor(authentication));
    }

    @PostMapping("/dashboards/{id}/edit-lock/renew")
    DashboardEditLock renew(@PathVariable UUID id, Authentication authentication) {
        return service.renewLock(id, actor(authentication));
    }

    @DeleteMapping("/dashboards/{id}/edit-lock")
    ResponseEntity<Void> release(@PathVariable UUID id, Authentication authentication) {
        service.releaseLock(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dashboards/{id}/validate")
    DashboardValidationResult validate(@PathVariable UUID id, Authentication authentication) {
        return service.validate(id, actor(authentication));
    }

    @PostMapping("/dashboards/{id}/publish")
    DashboardVersionView publish(@PathVariable UUID id, @RequestBody(required = false) PublishRequest request,
                                 Authentication authentication) {
        return service.publish(id, request, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/versions")
    List<DashboardVersionView> versions(@PathVariable UUID id, Authentication authentication) {
        return service.versions(id, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/versions/{versionId}")
    DashboardVersionView version(@PathVariable UUID id, @PathVariable UUID versionId,
                                 Authentication authentication) {
        return service.version(id, versionId, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/versions/diff")
    DashboardVersionDiff diff(@PathVariable UUID id, @RequestParam UUID from, @RequestParam UUID to,
                              Authentication authentication) {
        return service.diff(id, from, to, actor(authentication));
    }

    @PostMapping("/dashboards/{id}/versions/{versionId}/create-draft")
    DashboardDraftView createDraft(@PathVariable UUID id, @PathVariable UUID versionId,
                                   Authentication authentication) {
        return service.createDraftFromVersion(id, versionId, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/permissions")
    List<DashboardPermission> permissions(@PathVariable UUID id, Authentication authentication) {
        return service.permissions(id, actor(authentication));
    }

    @PutMapping("/dashboards/{id}/permissions")
    List<DashboardPermission> permissions(@PathVariable UUID id, @RequestBody DashboardPermissionsRequest request,
                                          Authentication authentication) {
        return service.putPermissions(id, request, actor(authentication));
    }

    @PutMapping("/dashboards/{id}/favorite")
    ResponseEntity<Void> favorite(@PathVariable UUID id, Authentication authentication) {
        service.favorite(id, true, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/dashboards/{id}/favorite")
    ResponseEntity<Void> unfavorite(@PathVariable UUID id, Authentication authentication) {
        service.favorite(id, false, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/dashboards/{id}/health")
    DashboardHealth health(@PathVariable UUID id, Authentication authentication) {
        return service.health(id, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/usage")
    DashboardUsage usage(@PathVariable UUID id, Authentication authentication) {
        return service.usage(id, actor(authentication));
    }

    @GetMapping("/dashboards/{id}/query-plan")
    DashboardQueryPlanView plan(@PathVariable UUID id, Authentication authentication) {
        return service.queryPlan(id, actor(authentication));
    }

    @PostMapping("/dashboard-query-plans/{planId}/execute")
    ResponseEntity<DashboardBatchResult> execute(@PathVariable UUID planId,
                                                 @RequestBody DashboardExecuteRequest request,
                                                 Authentication authentication) {
        return ResponseEntity.accepted().body(service.executeSingle(planId, request, actor(authentication)));
    }

    @PostMapping("/dashboard-query-plans/{planId}/widgets:batch")
    ResponseEntity<DashboardBatchResult> executeBatch(@PathVariable UUID planId,
                                                      @RequestBody DashboardExecuteRequest request,
                                                      Authentication authentication) {
        return ResponseEntity.accepted().body(service.execute(planId, request, actor(authentication)));
    }

    @PostMapping("/dashboard-query-plans/{planId}/filter-options")
    List<Map<String, Object>> filterOptions(@PathVariable UUID planId,
                                            @RequestBody FilterOptionsRequest request,
                                            Authentication authentication) {
        return service.filterOptions(planId, request, actor(authentication));
    }

    @PostMapping("/dashboard-query-plans/{planId}/drilldown-token")
    DrilldownToken drilldown(@PathVariable UUID planId, @RequestBody DrilldownRequest request,
                             Authentication authentication) {
        return service.drilldown(planId, request, actor(authentication));
    }

    private Actor actor(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String id = jwt.getSubject() == null ? jwt.getClaimAsString("preferred_username") : jwt.getSubject();
        String name = jwt.getClaimAsString("name");
        if (name == null) name = jwt.getClaimAsString("preferred_username");
        List<String> roles = authentication.getAuthorities().stream().map(value -> value.getAuthority())
                .filter(value -> value.startsWith("ROLE_")).map(value -> value.substring(5)).toList();
        return new Actor(id, name == null ? id : name, roles);
    }

    private long etag(String value) {
        try { return Long.parseLong(value.replace("W/", "").replace("\"", "").trim()); }
        catch (Exception exception) { throw new IllegalArgumentException("If-Match 必须包含有效 ETag"); }
    }
}
