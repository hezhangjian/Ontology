package com.hezhangjian.ontology.core.datasets;

import static com.hezhangjian.ontology.core.datasets.DatasetModels.*;

import java.net.URI;
import java.util.UUID;

import com.hezhangjian.ontology.core.security.ActorIdentity;
import com.hezhangjian.ontology.core.pipelines.PipelineModels.Actor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ontologies/{ontologyId}")
@PreAuthorize("hasAnyRole('Viewer','Builder','Admin')")
public class DatasetController {
    private final DatasetService service;
    public DatasetController(DatasetService service) { this.service = service; }

    @GetMapping("/datasets") DatasetPage list(@RequestParam(required = false) String search) { return service.list(search); }
    @GetMapping("/datasets/{id}") Dataset get(@PathVariable UUID id) { return service.get(id); }
    @DeleteMapping("/datasets/{id}")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
    @GetMapping("/datasets/{id}/preview") Preview preview(@PathVariable UUID id, @RequestParam(defaultValue="50") int limit, @RequestParam(defaultValue="0") int offset) { return service.preview(id, limit, offset); }
    @PostMapping("/datasets/{id}/query") QueryResult query(@PathVariable UUID id, @RequestBody QueryRequest request) { return service.query(id, request); }
    @GetMapping("/datasets/{id}/mapping-preview") MappingPreview mappingPreview(@PathVariable UUID id, @RequestParam String identityField, @RequestParam String titleField) { return service.mappingPreview(id, identityField, titleField); }

    @PostMapping("/pipelines/{pipelineId}/materialize-dataset")
    @PreAuthorize("hasAnyRole('Builder','Admin')")
    ResponseEntity<Dataset> materialize(@PathVariable UUID pipelineId, @RequestBody(required=false) MaterializeRequest request, Authentication authentication) {
        ActorIdentity actor = ActorIdentity.from(authentication);
        Dataset dataset = service.materialize(pipelineId, request, new Actor(actor.id(), actor.name(), actor.admin()));
        return ResponseEntity.created(URI.create("/v1/ontologies/" + com.hezhangjian.ontology.core.security.WorkspaceContext.id()
                + "/datasets/" + dataset.id())).body(dataset);
    }
}
