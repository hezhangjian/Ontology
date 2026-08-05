package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.ObjectTypeApi;
import com.hezhangjian.ontology.model.CreateObjectTypeReq;
import com.hezhangjian.ontology.model.ObjectType;
import com.hezhangjian.ontology.model.UpdateObjectTypeReq;
import com.hezhangjian.ontology.service.ObjectTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class ObjectTypeController implements ObjectTypeApi {
    private final ObjectTypeService objectTypeService;

    @Override
    public ResponseEntity<ObjectType> createObjectType(String ontologyId, CreateObjectTypeReq createObjectTypeReq) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(objectTypeService.createObjectType(ontologyId, createObjectTypeReq));
    }

    @Override
    public ResponseEntity<Void> deleteObjectType(String ontologyId, String objectTypeId) {
        objectTypeService.deleteObjectType(ontologyId, objectTypeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ObjectType> getObjectType(String ontologyId, String objectTypeId) {
        return ResponseEntity.ok(objectTypeService.getObjectType(ontologyId, objectTypeId));
    }

    @Override
    public ResponseEntity<List<ObjectType>> listObjectTypes(String ontologyId) {
        return ResponseEntity.ok(objectTypeService.listObjectTypes(ontologyId));
    }

    @Override
    public ResponseEntity<ObjectType> updateObjectType(
            String ontologyId, String objectTypeId, UpdateObjectTypeReq updateObjectTypeReq) {
        return ResponseEntity.ok(objectTypeService.updateObjectType(ontologyId, objectTypeId, updateObjectTypeReq));
    }
}
