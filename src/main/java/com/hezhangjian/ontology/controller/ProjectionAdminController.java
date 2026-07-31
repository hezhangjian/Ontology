package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.ProjectionAdminApi;
import com.hezhangjian.ontology.model.GraphSchemaRebuildResult;
import com.hezhangjian.ontology.model.RebuildResult;
import com.hezhangjian.ontology.service.ProjectionAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class ProjectionAdminController implements ProjectionAdminApi {
    private final ProjectionAdminService projectionAdminService;

    @Override
    public ResponseEntity<RebuildResult> rebuild(String ontologyId) {
        return ResponseEntity.ok(projectionAdminService.rebuildIndex(ontologyId));
    }

    @Override
    public ResponseEntity<GraphSchemaRebuildResult> rebuildGraphSchema(String ontologyId) {
        return ResponseEntity.ok(projectionAdminService.rebuildGraphSchema(ontologyId));
    }
}
