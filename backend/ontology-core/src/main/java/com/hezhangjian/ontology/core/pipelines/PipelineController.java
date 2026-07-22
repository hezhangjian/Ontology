package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.hezhangjian.ontology.core.connections.ConnectionProblem;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('Builder','Admin')")
public class PipelineController {
    private final PipelineService service;
    private final PipelineEventStreamService streams;

    public PipelineController(PipelineService service, PipelineEventStreamService streams) {
        this.service = service;
        this.streams = streams;
    }

    @GetMapping("/pipelines")
    PipelinePage list(@RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size,
                      @RequestParam(required = false) String search,
                      @RequestParam(required = false) String mode,
                      @RequestParam(required = false) String lifecycle,
                      @RequestParam(required = false) String runStatus,
                      @RequestParam(required = false) String owner,
                      @RequestParam(required = false) String sort) {
        return service.list(page, size, search, mode, lifecycle, runStatus, owner, sort);
    }

    @PostMapping("/pipelines")
    ResponseEntity<Pipeline> create(@RequestBody CreatePipelineRequest request, Authentication authentication) {
        Pipeline created = service.create(request, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/pipelines/" + created.id())).eTag(Long.toString(created.draft().etag())).body(created);
    }

    @GetMapping("/pipelines/{id}")
    ResponseEntity<Pipeline> get(@PathVariable UUID id) {
        Pipeline pipeline = service.get(id);
        long etag = pipeline.draft() == null ? pipeline.version() : pipeline.draft().etag();
        return ResponseEntity.ok().eTag(Long.toString(etag)).body(pipeline);
    }

    @PatchMapping("/pipelines/{id}/draft")
    ResponseEntity<Pipeline> updateDraft(@PathVariable UUID id, @RequestHeader("If-Match") String ifMatch,
                                         @RequestBody UpdateDraftRequest request, Authentication authentication) {
        Pipeline pipeline = service.updateDraft(id, parseVersion(ifMatch), request, actor(authentication));
        return ResponseEntity.ok().eTag(Long.toString(pipeline.draft().etag())).body(pipeline);
    }

    @PostMapping("/pipelines/{id}/duplicate")
    ResponseEntity<Pipeline> duplicate(@PathVariable UUID id, Authentication authentication) {
        Pipeline copy = service.duplicate(id, actor(authentication));
        return ResponseEntity.created(URI.create("/v1/pipelines/" + copy.id())).eTag(Long.toString(copy.draft().etag())).body(copy);
    }

    @PostMapping("/pipelines/{id}/validate")
    ValidationResult validate(@PathVariable UUID id) { return service.validate(id); }

    @PostMapping("/pipelines/{id}/preview")
    ResponseEntity<PreviewRun> preview(@PathVariable UUID id, @RequestBody PreviewRequest request,
                                       Authentication authentication) {
        return ResponseEntity.accepted().body(service.preview(id, request, actor(authentication)));
    }

    @GetMapping("/pipeline-node-types")
    List<NodeType> nodeTypes() { return service.nodeTypes(); }

    @GetMapping("/pipelines/{id}/versions")
    List<PipelineVersion> versions(@PathVariable UUID id) { return service.versions(id); }

    @GetMapping("/pipelines/{id}/versions/{version}")
    PipelineVersion version(@PathVariable UUID id, @PathVariable int version) { return service.version(id, version); }

    @GetMapping("/pipelines/{id}/diff")
    Map<String, Object> diff(@PathVariable UUID id) { return service.diff(id); }

    @GetMapping("/pipelines/{id}/proposals")
    List<PipelineProposal> proposals(@PathVariable UUID id) { return service.proposals(id); }

    @PostMapping("/pipelines/{id}/proposals")
    ResponseEntity<PipelineProposal> propose(@PathVariable UUID id, @RequestBody ProposalRequest request,
                                              Authentication authentication) {
        return ResponseEntity.accepted().body(service.propose(id, request, actor(authentication)));
    }

    @PostMapping("/pipelines/{id}/proposals/{proposalId}/approve")
    PipelineProposal approve(@PathVariable UUID id, @PathVariable UUID proposalId,
                             @RequestBody(required = false) DecisionRequest request, Authentication authentication) {
        return service.decide(id, proposalId, true, request, actor(authentication));
    }

    @PostMapping("/pipelines/{id}/proposals/{proposalId}/reject")
    PipelineProposal reject(@PathVariable UUID id, @PathVariable UUID proposalId,
                            @RequestBody(required = false) DecisionRequest request, Authentication authentication) {
        return service.decide(id, proposalId, false, request, actor(authentication));
    }

    @PostMapping("/pipelines/{id}/publish")
    PipelineVersion publish(@PathVariable UUID id, @RequestBody PublishRequest request,
                            Authentication authentication) {
        return service.publish(id, request, actor(authentication));
    }

    @PostMapping("/pipelines/{id}/rollback")
    @PreAuthorize("hasRole('Admin')")
    Pipeline rollback(@PathVariable UUID id, @RequestBody RollbackRequest request,
                      Authentication authentication) {
        return service.rollback(id, request, actor(authentication));
    }

    @PostMapping("/pipelines/{id}/run")
    ResponseEntity<PipelineRun> run(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(service.run(id, actor(authentication)));
    }

    @PostMapping("/pipelines/{id}/start")
    ResponseEntity<PipelineRun> start(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(service.start(id, actor(authentication)));
    }

    @PostMapping("/pipelines/{id}/stop")
    ResponseEntity<PipelineRun> stop(@PathVariable UUID id, @RequestBody(required = false) SavepointRequest request,
                                     Authentication authentication) {
        return ResponseEntity.accepted().body(service.stop(id, request, actor(authentication)));
    }

    @PostMapping("/pipelines/{id}/savepoint")
    ResponseEntity<PipelineRun> savepoint(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.accepted().body(service.savepoint(id, actor(authentication)));
    }

    @PostMapping("/pipelines/{id}/reset-offsets")
    @PreAuthorize("hasRole('Admin')")
    Pipeline resetOffsets(@PathVariable UUID id, @RequestBody OffsetResetRequest request,
                          Authentication authentication) {
        return service.resetOffsets(id, request, actor(authentication));
    }

    @PostMapping("/pipelines/{id}/pause")
    Pipeline pause(@PathVariable UUID id, Authentication authentication) { return service.pause(id, actor(authentication)); }

    @PostMapping("/pipelines/{id}/resume")
    Pipeline resume(@PathVariable UUID id, Authentication authentication) { return service.resume(id, actor(authentication)); }

    @PostMapping("/pipelines/{id}/archive")
    Pipeline archive(@PathVariable UUID id, Authentication authentication) { return service.archive(id, actor(authentication)); }

    @DeleteMapping("/pipelines/{id}")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        service.delete(id, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/pipelines/{id}/runs")
    PipelineRunPage runs(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        return service.runs(id, page, size);
    }

    @GetMapping("/pipeline-runs/{runId}")
    RunDetail run(@PathVariable UUID runId) { return service.runDetail(runId); }

    @GetMapping("/pipeline-runs/{runId}/events")
    List<RunEvent> events(@PathVariable UUID runId) { return service.runDetail(runId).events(); }

    @GetMapping(path = "/pipeline-runs/{runId}/events/stream", produces = "text/event-stream")
    SseEmitter eventStream(@PathVariable UUID runId,
                           @RequestParam(defaultValue = "0") long afterSequence) {
        return streams.stream(runId, afterSequence);
    }

    @GetMapping("/pipeline-runs/{runId}/logs")
    List<Map<String, Object>> logs(@PathVariable UUID runId) { return service.runDetail(runId).logs(); }

    @GetMapping("/pipeline-runs/{runId}/metrics")
    Map<String, Object> metrics(@PathVariable UUID runId) { return service.runDetail(runId).metrics(); }

    @PostMapping("/pipeline-runs/{runId}/cancel")
    ResponseEntity<PipelineRun> cancel(@PathVariable UUID runId, Authentication authentication) {
        return ResponseEntity.accepted().body(service.cancel(runId, actor(authentication)));
    }

    @PostMapping("/pipeline-runs/{runId}/retry")
    ResponseEntity<PipelineRun> retry(@PathVariable UUID runId, Authentication authentication) {
        return ResponseEntity.accepted().body(service.retry(runId, actor(authentication)));
    }

    @PostMapping("/pipeline-runs/{runId}/replay-dlq")
    @PreAuthorize("hasRole('Admin')")
    ResponseEntity<PipelineRun> replayDlq(@PathVariable UUID runId, Authentication authentication) {
        return ResponseEntity.accepted().body(service.replayDlq(runId, actor(authentication)));
    }

    @PostMapping("/pipeline-previews/{previewId}/cancel")
    ResponseEntity<Void> cancelPreview(@PathVariable UUID previewId, Authentication authentication) {
        service.cancelPreview(previewId, actor(authentication));
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/pipeline-previews/{previewId}")
    PreviewRun preview(@PathVariable UUID previewId) { return service.preview(previewId); }

    private Actor actor(Authentication authentication) {
        ActorIdentity identity = ActorIdentity.from(authentication);
        return new Actor(identity.id(), identity.name(), identity.admin());
    }

    private long parseVersion(String ifMatch) {
        try { return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim()); }
        catch (NumberFormatException cause) { throw new ConnectionProblem("PIPELINE_VERSION_INVALID", "If-Match 版本格式无效"); }
    }
}
