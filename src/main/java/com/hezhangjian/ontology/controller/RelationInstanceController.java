package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.RelationInstanceApi;
import com.hezhangjian.ontology.model.RelationInstancePage;
import com.hezhangjian.ontology.model.RelationInstanceQueryReq;
import com.hezhangjian.ontology.service.RelationInstanceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class RelationInstanceController implements RelationInstanceApi {
    private final RelationInstanceQueryService instanceQueryService;

    @Override
    public ResponseEntity<RelationInstancePage> listRelationInstances(
            String objectId,
            String objectTypeId,
            String ontologyId,
            String linkTypeId,
            String direction,
            Integer pageSize) {
        return ResponseEntity.ok(instanceQueryService.listRelations(
                ontologyId,
                objectTypeId,
                objectId,
                linkTypeId,
                direction,
                pageSize));
    }

    @Override
    public ResponseEntity<RelationInstancePage> queryRelationInstances(
            String ontologyId, RelationInstanceQueryReq relationInstanceQueryReq) {
        return ResponseEntity.ok(instanceQueryService.queryRelations(
                ontologyId, relationInstanceQueryReq));
    }
}
