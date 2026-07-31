package com.hezhangjian.ontology.controller;

import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.OBJECT_TYPE;

import com.hezhangjian.ontology.api.ObjectTypeApi;
import com.hezhangjian.ontology.model.ResourceDraftRequest;
import com.hezhangjian.ontology.model.ResourceIdentityRequest;
import com.hezhangjian.ontology.model.ResourceView;
import com.hezhangjian.ontology.model.CreateObjectInstanceImportReq;
import com.hezhangjian.ontology.service.ModelingApiService;
import com.hezhangjian.ontology.service.ModelingApiService.VersionedResource;
import com.hezhangjian.ontology.service.ObjectInstanceImportService;
import com.hezhangjian.ontology.service.ObjectInstanceSchemaService;
import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ObjectTypeController implements ObjectTypeApi {
    private final ModelingApiService modelingService;
    private final ObjectInstanceSchemaService instanceSchemas;
    private final ObjectInstanceImportService imports;

    @Autowired
    public ObjectTypeController(
            ModelingApiService modelingService,
            ObjectInstanceSchemaService instanceSchemas,
            ObjectInstanceImportService imports) {
        this.modelingService = modelingService;
        this.instanceSchemas = instanceSchemas;
        this.imports = imports;
    }

    @Override
    @Transactional
    public ResponseEntity<ResourceView> createObjectType(
            String ontologyId, ResourceDraftRequest resourceDraftRequest) {
        VersionedResource value =
                modelingService.create(ontologyId, OBJECT_TYPE, resourceDraftRequest);
        instanceSchemas.provision(ontologyId, value.resource().getId());
        startDatasetImport(ontologyId, value.resource().getId(), resourceDraftRequest);
        URI location = URI.create("/v1/ontologies/" + ontologyId + "/object-types/"
                + value.resource().getId());
        return ResponseEntity.created(location)
                .eTag(Long.toString(value.etag()))
                .body(value.resource());
    }

    @Override
    @Transactional
    public ResponseEntity<Void> deleteObjectType(
            String objectTypeId, String ontologyId) {
        instanceSchemas.tombstone(ontologyId, objectTypeId);
        modelingService.delete(ontologyId, objectTypeId, OBJECT_TYPE);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ResourceView> getObjectType(
            String objectTypeId, String ontologyId) {
        return current(modelingService.get(ontologyId, objectTypeId, OBJECT_TYPE));
    }

    @Override
    public ResponseEntity<List<ResourceView>> listObjectTypes(
            String ontologyId, String search) {
        return ResponseEntity.ok(modelingService.list(ontologyId, OBJECT_TYPE, search));
    }

    @Override
    public ResponseEntity<ResourceView> updateObjectType(
            String objectTypeId,
            String ontologyId,
            ResourceIdentityRequest resourceIdentityRequest) {
        return current(modelingService.updateIdentity(
                ontologyId, objectTypeId, OBJECT_TYPE, resourceIdentityRequest));
    }

    private ResponseEntity<ResourceView> current(VersionedResource value) {
        return ResponseEntity.ok()
                .eTag(Long.toString(value.etag()))
                .body(value.resource());
    }

    private void startDatasetImport(
            String ontologyId,
            String objectTypeId,
            ResourceDraftRequest request) {
        if (request.getDatasetId() == null
                || request.getDatasetMapping() == null
                || request.getDatasetMapping().isEmpty()
                || request.getProperties() == null) {
            return;
        }
        String primaryProperty = request.getProperties().stream()
                .filter(property -> Boolean.TRUE.equals(property.getPrimaryKey()))
                .map(property -> property.getApiName())
                .findFirst()
                .orElseThrow();
        String titleProperty = request.getProperties().stream()
                .filter(property -> Boolean.TRUE.equals(property.getTitleProperty()))
                .map(property -> property.getApiName())
                .findFirst()
                .orElse(primaryProperty);
        String identityField = sourceField(request, primaryProperty);
        String titleField = sourceField(request, titleProperty);
        imports.create(
                ontologyId,
                objectTypeId,
                new CreateObjectInstanceImportReq()
                        .datasetId(request.getDatasetId())
                        .identityField(identityField)
                        .titleField(titleField)
                        .fieldMappings(request.getDatasetMapping())
                        .mode(CreateObjectInstanceImportReq.ModeEnum.UPSERT));
    }

    private String sourceField(ResourceDraftRequest request, String propertyId) {
        return request.getDatasetMapping().entrySet().stream()
                .filter(entry -> propertyId.equals(entry.getValue()))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dataset mapping must include primary key and title properties"));
    }
}
