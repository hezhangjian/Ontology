package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1")
public class PipelineInternalController {
    private final PipelineRuntimeCoordinator runtime;

    public PipelineInternalController(PipelineRuntimeCoordinator runtime) {
        this.runtime = runtime;
    }

    @PostMapping("/workload-credentials/exchange")
    WorkloadExchangeResponse exchange(@RequestBody WorkloadExchangeRequest request,
                                      @RequestHeader("X-Workload-Token") String token) {
        return runtime.exchange(request, token);
    }

    @PostMapping("/workload-credentials/preview-exchange")
    PreviewExchangeResponse exchangePreview(@RequestBody PreviewExchangeRequest request,
                                             @RequestHeader("X-Workload-Token") String token) {
        return runtime.exchangePreview(request, token);
    }

    @PostMapping("/pipeline-previews/{previewId}/result")
    ResponseEntity<Void> previewResult(@PathVariable UUID previewId, @RequestBody PreviewResultRequest request,
                                       @RequestHeader("X-Workload-Token") String token) {
        runtime.completePreview(previewId, request, token);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/projection/ack")
    ResponseEntity<Void> projectionAck(@RequestBody ProjectionAckRequest request,
                                       @RequestHeader("X-Workload-Token") String token) {
        runtime.projectionAck(request, token);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/pipeline-runs/{runId}/progress")
    ResponseEntity<Void> progress(@PathVariable UUID runId, @RequestBody RuntimeProgressRequest request,
                                  @RequestHeader("X-Workload-Token") String token) {
        runtime.progress(runId, request, token);
        return ResponseEntity.accepted().build();
    }
}
