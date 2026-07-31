package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.exception.ConnectionProblem;
import com.hezhangjian.ontology.security.WorkspaceContext;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RequiredArgsConstructor
@Service
public class PipelineApiService {
    private final OntologyLookupService catalogs;
    private final ObjectMapper objectMapper;
    private final PipelineService pipelines;
    private final PipelineEventStreamService streams;

    public PipelinePage list(String ontologyApiName, Integer page, Integer size, String search,
            String mode, String lifecycle, String runStatus, String owner, String sort) {
        return inOntology(ontologyApiName, () -> convert(
                pipelines.list(page, size, search, mode, lifecycle, runStatus, owner, sort),
                PipelinePage.class));
    }

    public Versioned<Pipeline> create(String ontologyApiName, CreatePipelineRequest request) {
        return inOntology(ontologyApiName, () -> pipeline(
                pipelines.create(request(request, PipelineModels.CreatePipelineRequest.class), actor())));
    }

    public Versioned<Pipeline> get(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () -> pipeline(pipelines.get(id)));
    }

    public Versioned<PipelineDraft> draft(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () -> {
            PipelineModels.PipelineDraft value = pipelines.get(id).draft();
            return new Versioned<>(convert(value, PipelineDraft.class), Long.toString(value.etag()));
        });
    }

    public Versioned<Pipeline> updateDraft(
            String ontologyApiName, UUID id, String ifMatch, UpdateDraftRequest request) {
        return inOntology(ontologyApiName, () -> pipeline(pipelines.updateDraft(
                id,
                parseEtag(ifMatch),
                request(request, PipelineModels.UpdateDraftRequest.class),
                actor())));
    }

    public Versioned<Pipeline> duplicate(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () -> pipeline(pipelines.duplicate(id, actor())));
    }

    public ValidationResult validate(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.validate(id), ValidationResult.class));
    }

    public PreviewRun preview(String ontologyApiName, UUID id, PreviewRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                pipelines.preview(
                        id, request(request, PipelineModels.PreviewRequest.class), actor()),
                PreviewRun.class));
    }

    public PreviewRun preview(String ontologyApiName, UUID previewId) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.preview(previewId), PreviewRun.class));
    }

    public void cancelPreview(String ontologyApiName, UUID previewId) {
        inOntology(ontologyApiName, () -> {
            pipelines.cancelPreview(previewId, actor());
            return null;
        });
    }

    public List<NodeType> nodeTypes(String ontologyApiName) {
        return inOntology(ontologyApiName, () ->
                convertList(pipelines.nodeTypes(), NodeType.class));
    }

    public Pipeline publish(String ontologyApiName, UUID id, PublishRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                pipelines.publish(
                        id, request(request, PipelineModels.PublishRequest.class), actor()),
                Pipeline.class));
    }

    public PipelineRun run(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.run(id, actor()), PipelineRun.class));
    }

    public PipelineRun start(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.start(id, actor()), PipelineRun.class));
    }

    public PipelineRun stop(String ontologyApiName, UUID id, SavepointRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                pipelines.stop(
                        id, request(request, PipelineModels.SavepointRequest.class), actor()),
                PipelineRun.class));
    }

    public PipelineRun savepoint(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.savepoint(id, actor()), PipelineRun.class));
    }

    public Pipeline resetOffsets(
            String ontologyApiName, UUID id, OffsetResetRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                pipelines.resetOffsets(
                        id,
                        request(request, PipelineModels.OffsetResetRequest.class),
                        actor()),
                Pipeline.class));
    }

    public Pipeline pause(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.pause(id, actor()), Pipeline.class));
    }

    public Pipeline resume(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.resume(id, actor()), Pipeline.class));
    }

    public Pipeline archive(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.archive(id, actor()), Pipeline.class));
    }

    public void delete(String ontologyApiName, UUID id) {
        inOntology(ontologyApiName, () -> {
            pipelines.delete(id, actor());
            return null;
        });
    }

    public PipelineRunPage runs(String ontologyApiName, UUID id, Integer page, Integer size) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.runs(id, page, size), PipelineRunPage.class));
    }

    public RunDetail runDetail(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.runDetail(runId), RunDetail.class));
    }

    public List<RunEvent> events(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () ->
                convertList(pipelines.runDetail(runId).events(), RunEvent.class));
    }

    public List<Map<String, Object>> logs(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () -> pipelines.runDetail(runId).logs());
    }

    public Map<String, Object> metrics(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () -> pipelines.runDetail(runId).metrics());
    }

    public SseEmitter eventStream(String ontologyApiName, UUID runId, Long afterSequence) {
        return inOntology(ontologyApiName, () -> {
            pipelines.runById(runId);
            return streams.stream(runId, afterSequence);
        });
    }

    public PipelineRun cancel(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.cancel(runId, actor()), PipelineRun.class));
    }

    public PipelineRun retry(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.retry(runId, actor()), PipelineRun.class));
    }

    public PipelineRun replayDlq(String ontologyApiName, UUID runId) {
        return inOntology(ontologyApiName, () ->
                convert(pipelines.replayDlq(runId, actor()), PipelineRun.class));
    }

    private Versioned<Pipeline> pipeline(PipelineModels.Pipeline value) {
        long etag = value.draft() == null ? value.version() : value.draft().etag();
        return new Versioned<>(convert(value, Pipeline.class), Long.toString(etag));
    }

    private PipelineModels.Actor actor() {
        return new PipelineModels.Actor("local", "Local", true);
    }

    private <T> T inOntology(String apiName, Supplier<T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, work);
    }

    private long parseEtag(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException cause) {
            throw new ConnectionProblem(
                    "PIPELINE_ETAG_INVALID", "If-Match ETag 格式无效");
        }
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

    public record Versioned<T>(T value, String etag) {}
}
