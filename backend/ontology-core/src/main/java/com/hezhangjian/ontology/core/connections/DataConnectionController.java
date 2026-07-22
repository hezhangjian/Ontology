package com.hezhangjian.ontology.core.connections;

import static com.hezhangjian.ontology.core.connections.ConnectionModels.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.hezhangjian.ontology.core.security.ActorIdentity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('Builder','Admin')")
public class DataConnectionController {
    private final DataConnectionService service;

    public DataConnectionController(DataConnectionService service) {
        this.service = service;
    }

    @GetMapping("/data-sources")
    DataSourcePage list(@RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @RequestParam(required = false) String search,
                        @RequestParam(required = false) String type,
                        @RequestParam(required = false) String status,
                        @RequestParam(required = false) String owner) {
        return service.list(page, size, search, type, status, owner);
    }

    @PostMapping("/data-sources/test")
    TestResult test(@RequestBody TestRequest request, Authentication authentication) {
        return service.test(request, actor(authentication));
    }

    @PostMapping("/data-sources")
    ResponseEntity<DataSource> create(@RequestBody CreateRequest request, Authentication authentication) {
        DataSource created = service.create(request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/data-sources/" + created.id())).eTag(Long.toString(created.version())).body(created);
    }

    @PostMapping(value = "/data-sources/local-csv", consumes = "multipart/form-data")
    ResponseEntity<DataSource> importLocalCsv(@RequestParam String name,
                                               @RequestParam(required = false) String description,
                                               @RequestParam(required = false) List<String> tags,
                                               @RequestParam("files") List<MultipartFile> files,
                                               Authentication authentication) {
        DataSource created = service.importLocalCsv(name, description, tags, files, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/data-sources/" + created.id())).eTag(Long.toString(created.version())).body(created);
    }

    @GetMapping("/data-sources/{id}")
    ResponseEntity<DataSource> get(@PathVariable UUID id) {
        DataSource source = service.get(id);
        return ResponseEntity.ok().eTag(Long.toString(source.version())).body(source);
    }

    @GetMapping("/data-sources/{id}/overview")
    Overview overview(@PathVariable UUID id) {
        return service.overview(id);
    }

    @PatchMapping("/data-sources/{id}")
    ResponseEntity<DataSource> update(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                      @RequestBody UpdateRequest request, Authentication authentication) {
        DataSource source = service.update(id, parseVersion(ifMatch), request, actor(authentication));
        return ResponseEntity.ok().eTag(Long.toString(source.version())).body(source);
    }

    @PostMapping("/data-sources/{id}/test")
    TestResult retest(@PathVariable UUID id, Authentication authentication) {
        return service.retest(id, actor(authentication));
    }

    @PostMapping("/data-sources/{id}/disable")
    DataSource disable(@PathVariable UUID id, Authentication authentication) {
        return service.disable(id, actor(authentication));
    }

    @PostMapping("/data-sources/{id}/enable")
    DataSource enable(@PathVariable UUID id, Authentication authentication) {
        return service.enable(id, actor(authentication));
    }

    @DeleteMapping("/data-sources/{id}")
    @PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/data-sources/{id}/rotate-credential")
    CredentialSummary rotate(@PathVariable UUID id, @RequestBody RotateCredentialRequest request,
                             Authentication authentication) {
        return service.rotate(id, request.credential(), actor(authentication));
    }

    @GetMapping("/credentials")
    List<CredentialSummary> credentials(@RequestParam(defaultValue = "true") boolean usable,
                                        Authentication authentication) {
        return service.credentials(actor(authentication));
    }

    @GetMapping("/data-sources/{id}/assets")
    AssetPage assets(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "50") int size,
                     @RequestParam(required = false) String search) {
        return service.assets(id, page, size, search);
    }

    @PostMapping("/data-sources/{id}/discover")
    ResponseEntity<DiscoveryRun> discover(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(service.discover(id, actor(authentication)));
    }

    @GetMapping("/data-sources/{id}/assets/{assetId}")
    DataSourceAsset asset(@PathVariable UUID id, @PathVariable UUID assetId) {
        return service.asset(id, assetId);
    }

    @PostMapping("/data-sources/{id}/assets/{assetId}/infer-schema")
    ResponseEntity<DiscoveryRun> inferSchema(@PathVariable UUID id, @PathVariable UUID assetId,
                                             Authentication authentication) {
        return ResponseEntity.accepted().body(service.inferSchema(id, assetId, actor(authentication)));
    }

    @PostMapping("/data-sources/{id}/assets/{assetId}/preview")
    AssetPreview preview(@PathVariable UUID id, @PathVariable UUID assetId,
                         @RequestBody(required = false) PreviewRequest request, Authentication authentication) {
        return service.preview(id, assetId, request == null ? 50 : request.limit(), actor(authentication));
    }

    @GetMapping("/data-sources/{id}/assets/{assetId}/usage")
    AssetUsage usage(@PathVariable UUID id, @PathVariable UUID assetId) {
        return service.usage(id, assetId);
    }

    @GetMapping("/data-sources/{id}/pipelines")
    List<PipelineSummary> pipelines(@PathVariable UUID id) {
        return service.pipelines(id);
    }

    @GetMapping("/data-sources/{id}/runs")
    List<PipelineRunSummary> runs(@PathVariable UUID id) {
        return service.runs(id);
    }

    private Actor actor(Authentication authentication) {
        ActorIdentity identity = ActorIdentity.from(authentication);
        return new Actor(identity.id(), identity.name(), identity.admin());
    }

    private long parseVersion(String ifMatch) {
        try { return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim()); }
        catch (NumberFormatException cause) { throw new ConnectionProblem("VERSION_INVALID", "If-Match 版本格式无效"); }
    }

    public record PreviewRequest(int limit) { }
}
