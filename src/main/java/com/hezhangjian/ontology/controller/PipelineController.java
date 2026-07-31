package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.PipelineApi;
import com.hezhangjian.ontology.model.CreatePipelineRequest;
import com.hezhangjian.ontology.model.NodeType;
import com.hezhangjian.ontology.model.OffsetResetRequest;
import com.hezhangjian.ontology.model.Pipeline;
import com.hezhangjian.ontology.model.PipelineDraft;
import com.hezhangjian.ontology.model.PipelinePage;
import com.hezhangjian.ontology.model.PipelineRun;
import com.hezhangjian.ontology.model.PipelineRunPage;
import com.hezhangjian.ontology.model.PreviewRequest;
import com.hezhangjian.ontology.model.PreviewRun;
import com.hezhangjian.ontology.model.PublishRequest;
import com.hezhangjian.ontology.model.RunDetail;
import com.hezhangjian.ontology.model.RunEvent;
import com.hezhangjian.ontology.model.SavepointRequest;
import com.hezhangjian.ontology.model.UpdateDraftRequest;
import com.hezhangjian.ontology.model.ValidationResult;
import com.hezhangjian.ontology.service.PipelineApiService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RequiredArgsConstructor
@RestController
public class PipelineController implements PipelineApi {
    private final PipelineApiService pipelineService;

    @Override
    public ResponseEntity<Pipeline> archivePipeline(UUID id, String ontologyId) {
        return ResponseEntity.ok(pipelineService.archive(ontologyId, id));
    }

    @Override
    public ResponseEntity<PipelinePage> listPipelines(
            String ontologyId, Integer page, Integer size, String search, String mode,
            String lifecycle, String runStatus, String owner, String sort) {
        return ResponseEntity.ok(pipelineService.list(
                ontologyId, page, size, search, mode, lifecycle, runStatus, owner, sort));
    }

    @Override
    public ResponseEntity<PipelineRun> cancel(String ontologyId, UUID runId) {
        return ResponseEntity.accepted().body(pipelineService.cancel(ontologyId, runId));
    }

    @Override
    public ResponseEntity<Void> cancelPreview(String ontologyId, UUID previewId) {
        pipelineService.cancelPreview(ontologyId, previewId);
        return ResponseEntity.accepted().build();
    }

    @Override
    public ResponseEntity<Pipeline> createPipeline(
            String ontologyId, CreatePipelineRequest request) {
        PipelineApiService.Versioned<Pipeline> created =
                pipelineService.create(ontologyId, request);
        URI location = URI.create(
                "/v1/ontologies/" + ontologyId + "/pipelines/" + created.value().getId());
        return ResponseEntity.created(location)
                .eTag(created.etag())
                .body(created.value());
    }

    @Override
    public ResponseEntity<Void> deletePipeline(UUID id, String ontologyId) {
        pipelineService.delete(ontologyId, id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<PipelineDraft> getPipelineDraft(UUID id, String ontologyId) {
        PipelineApiService.Versioned<PipelineDraft> draft =
                pipelineService.draft(ontologyId, id);
        return ResponseEntity.ok().eTag(draft.etag()).body(draft.value());
    }

    @Override
    public ResponseEntity<Pipeline> duplicate(UUID id, String ontologyId) {
        PipelineApiService.Versioned<Pipeline> copy =
                pipelineService.duplicate(ontologyId, id);
        URI location = URI.create(
                "/v1/ontologies/" + ontologyId + "/pipelines/" + copy.value().getId());
        return ResponseEntity.created(location).eTag(copy.etag()).body(copy.value());
    }

    @Override
    public ResponseEntity<SseEmitter> eventStream(
            String ontologyId, UUID runId, Long afterSequence) {
        return ResponseEntity.ok(
                pipelineService.eventStream(ontologyId, runId, afterSequence));
    }

    @Override
    public ResponseEntity<List<RunEvent>> listPipelineRunEvents(String ontologyId, UUID runId) {
        return ResponseEntity.ok(pipelineService.events(ontologyId, runId));
    }

    @Override
    public ResponseEntity<Pipeline> getPipeline(UUID id, String ontologyId) {
        PipelineApiService.Versioned<Pipeline> value =
                pipelineService.get(ontologyId, id);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<List<Map<String, Object>>> getPipelineRunLogs(
            String ontologyId, UUID runId) {
        return ResponseEntity.ok(pipelineService.logs(ontologyId, runId));
    }

    @Override
    public ResponseEntity<Map<String, Object>> getPipelineRunMetrics(
            String ontologyId, UUID runId) {
        return ResponseEntity.ok(pipelineService.metrics(ontologyId, runId));
    }

    @Override
    public ResponseEntity<List<NodeType>> nodeTypes(String ontologyId) {
        return ResponseEntity.ok(pipelineService.nodeTypes(ontologyId));
    }

    @Override
    public ResponseEntity<Pipeline> pause(UUID id, String ontologyId) {
        return ResponseEntity.ok(pipelineService.pause(ontologyId, id));
    }

    @Override
    public ResponseEntity<PreviewRun> startPipelinePreview(
            UUID id, String ontologyId, PreviewRequest request) {
        return ResponseEntity.accepted().body(
                pipelineService.preview(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<PreviewRun> getPipelinePreview(String ontologyId, UUID previewId) {
        return ResponseEntity.ok(pipelineService.preview(ontologyId, previewId));
    }

    @Override
    public ResponseEntity<Pipeline> publishPipeline(
            UUID id, String ontologyId, PublishRequest request) {
        return ResponseEntity.ok(pipelineService.publish(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<PipelineRun> replayDlq(String ontologyId, UUID runId) {
        return ResponseEntity.accepted().body(
                pipelineService.replayDlq(ontologyId, runId));
    }

    @Override
    public ResponseEntity<Pipeline> resetOffsets(
            UUID id, String ontologyId, OffsetResetRequest request) {
        return ResponseEntity.ok(pipelineService.resetOffsets(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<Pipeline> resume(UUID id, String ontologyId) {
        return ResponseEntity.ok(pipelineService.resume(ontologyId, id));
    }

    @Override
    public ResponseEntity<PipelineRun> retryPipelineRun(String ontologyId, UUID runId) {
        return ResponseEntity.accepted().body(pipelineService.retry(ontologyId, runId));
    }

    @Override
    public ResponseEntity<PipelineRun> startBatchPipelineRun(UUID id, String ontologyId) {
        return ResponseEntity.accepted().body(pipelineService.run(ontologyId, id));
    }

    @Override
    public ResponseEntity<RunDetail> getPipelineRun(String ontologyId, UUID runId) {
        return ResponseEntity.ok(pipelineService.runDetail(ontologyId, runId));
    }

    @Override
    public ResponseEntity<PipelineRunPage> runs(
            UUID id, String ontologyId, Integer page, Integer size) {
        return ResponseEntity.ok(pipelineService.runs(ontologyId, id, page, size));
    }

    @Override
    public ResponseEntity<PipelineRun> savepoint(UUID id, String ontologyId) {
        return ResponseEntity.accepted().body(pipelineService.savepoint(ontologyId, id));
    }

    @Override
    public ResponseEntity<PipelineRun> start(UUID id, String ontologyId) {
        return ResponseEntity.accepted().body(pipelineService.start(ontologyId, id));
    }

    @Override
    public ResponseEntity<PipelineRun> stop(
            UUID id, String ontologyId, SavepointRequest request) {
        return ResponseEntity.accepted().body(
                pipelineService.stop(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<Pipeline> updateDraft(
            String ifMatch, UUID id, String ontologyId, UpdateDraftRequest request) {
        PipelineApiService.Versioned<Pipeline> value =
                pipelineService.updateDraft(ontologyId, id, ifMatch, request);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<ValidationResult> validatePipeline(UUID id, String ontologyId) {
        return ResponseEntity.ok(pipelineService.validate(ontologyId, id));
    }
}
