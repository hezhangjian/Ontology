package com.hezhangjian.ontology.controller;

import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.ACTION;
import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.FUNCTION;
import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.INTERFACE;
import static com.hezhangjian.ontology.service.ModelingModels.ResourceKind.LINK_TYPE;

import com.hezhangjian.ontology.api.ModelingApi;
import com.hezhangjian.ontology.service.ModelingModels.ResourceKind;
import com.hezhangjian.ontology.model.ActionExecuteRequest;
import com.hezhangjian.ontology.model.ActionExecution;
import com.hezhangjian.ontology.model.ActionPreview;
import com.hezhangjian.ontology.model.ActionPreviewRequest;
import com.hezhangjian.ontology.model.FunctionExecution;
import com.hezhangjian.ontology.model.FunctionTestRequest;
import com.hezhangjian.ontology.model.HealthIssue;
import com.hezhangjian.ontology.model.ModelingSummary;
import com.hezhangjian.ontology.model.ObjectTypeBackingView;
import com.hezhangjian.ontology.model.PropertyView;
import com.hezhangjian.ontology.model.ResourceDraftRequest;
import com.hezhangjian.ontology.model.ResourceIdentityRequest;
import com.hezhangjian.ontology.model.ResourceView;
import com.hezhangjian.ontology.model.SearchResult;
import com.hezhangjian.ontology.service.ModelingApiService;
import com.hezhangjian.ontology.service.ModelingApiService.VersionedResource;
import com.hezhangjian.ontology.service.ObjectInstanceSchemaService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class ModelingController implements ModelingApi {
    private final ModelingApiService modelingService;
    private final ObjectInstanceSchemaService instanceSchemas;

    @Override
    public ResponseEntity<ResourceView> action(String id, String ontologyId) {
        return current(modelingService.get(ontologyId, id, ACTION));
    }

    @Override
    public ResponseEntity<ActionExecution> actionExecution(UUID id, String ontologyId) {
        return ResponseEntity.ok(modelingService.actionExecution(ontologyId, id));
    }

    @Override
    public ResponseEntity<List<ActionExecution>> actionExecutions(
            String ontologyId, String status) {
        return ResponseEntity.ok(modelingService.actionExecutions(ontologyId, status));
    }

    @Override
    public ResponseEntity<List<ResourceView>> listActionTypes(String ontologyId, String search) {
        return ResponseEntity.ok(modelingService.list(ontologyId, ACTION, search));
    }

    @Override
    public ResponseEntity<ResourceView> createAction(
            String ontologyId, ResourceDraftRequest resourceDraftRequest) {
        return created(modelingService.create(ontologyId, ACTION, resourceDraftRequest),
                ontologyId, "action-types");
    }

    @Override
    public ResponseEntity<ResourceView> createFunction(
            String ontologyId, ResourceDraftRequest resourceDraftRequest) {
        return created(modelingService.create(ontologyId, FUNCTION, resourceDraftRequest),
                ontologyId, "functions");
    }

    @Override
    public ResponseEntity<ResourceView> createFunctionDraft(
            Long ifMatch,
            String id,
            String ontologyId,
            ResourceDraftRequest resourceDraftRequest) {
        return current(modelingService.createFunctionDraft(
                ontologyId, id, resourceDraftRequest, ifMatch));
    }

    @Override
    public ResponseEntity<ResourceView> createInterface(
            String ontologyId, ResourceDraftRequest resourceDraftRequest) {
        return created(modelingService.create(ontologyId, INTERFACE, resourceDraftRequest),
                ontologyId, "interfaces");
    }

    @Override
    public ResponseEntity<ResourceView> createLinkType(
            String ontologyId, ResourceDraftRequest resourceDraftRequest) {
        return created(modelingService.create(ontologyId, LINK_TYPE, resourceDraftRequest),
                ontologyId, "link-types");
    }

    @Override
    public ResponseEntity<ResourceView> createObjectDraft(
            String ifMatch,
            String id,
            String ontologyId,
            ResourceDraftRequest resourceDraftRequest) {
        VersionedResource value = modelingService.createObjectDraft(
                ontologyId, id, resourceDraftRequest, ifMatch);
        instanceSchemas.provision(ontologyId, id);
        return current(value);
    }

    @Override
    public ResponseEntity<Void> deleteAction(String id, String ontologyId) {
        return delete(ontologyId, id, ACTION);
    }

    @Override
    public ResponseEntity<Void> deleteFunction(String id, String ontologyId) {
        return delete(ontologyId, id, FUNCTION);
    }

    @Override
    public ResponseEntity<Void> deleteInterface(String id, String ontologyId) {
        return delete(ontologyId, id, INTERFACE);
    }

    @Override
    public ResponseEntity<Void> deleteLinkType(String id, String ontologyId) {
        return delete(ontologyId, id, LINK_TYPE);
    }

    @Override
    public ResponseEntity<ActionExecution> executeAction(
            String idempotencyKey,
            String id,
            String ontologyId,
            ActionExecuteRequest actionExecuteRequest) {
        return ResponseEntity.accepted().body(modelingService.executeAction(
                ontologyId, id, actionExecuteRequest, idempotencyKey));
    }

    @Override
    public ResponseEntity<ResourceView> function(String id, String ontologyId) {
        return current(modelingService.get(ontologyId, id, FUNCTION));
    }

    @Override
    public ResponseEntity<List<ResourceView>> listFunctions(String ontologyId, String search) {
        return ResponseEntity.ok(modelingService.list(ontologyId, FUNCTION, search));
    }

    @Override
    public ResponseEntity<List<HealthIssue>> getModelingHealth(String ontologyId) {
        return ResponseEntity.ok(modelingService.health(ontologyId));
    }

    @Override
    public ResponseEntity<ResourceView> interfaceType(String id, String ontologyId) {
        return current(modelingService.get(ontologyId, id, INTERFACE));
    }

    @Override
    public ResponseEntity<List<ResourceView>> interfaces(String ontologyId, String search) {
        return ResponseEntity.ok(modelingService.list(ontologyId, INTERFACE, search));
    }

    @Override
    public ResponseEntity<ResourceView> linkType(String id, String ontologyId) {
        return current(modelingService.get(ontologyId, id, LINK_TYPE));
    }

    @Override
    public ResponseEntity<List<ResourceView>> linkTypes(String ontologyId, String search) {
        return ResponseEntity.ok(modelingService.list(ontologyId, LINK_TYPE, search));
    }

    @Override
    public ResponseEntity<ObjectTypeBackingView> objectTypeBacking(
            String id, String ontologyId) {
        return ResponseEntity.ok(modelingService.objectTypeBacking(ontologyId, id));
    }

    @Override
    public ResponseEntity<ActionPreview> previewAction(
            String id, String ontologyId, ActionPreviewRequest actionPreviewRequest) {
        return ResponseEntity.ok(
                modelingService.previewAction(ontologyId, id, actionPreviewRequest));
    }

    @Override
    public ResponseEntity<List<PropertyView>> listProperties(
            String ontologyId, UUID objectTypeId) {
        return ResponseEntity.ok(modelingService.properties(ontologyId, objectTypeId));
    }

    @Override
    public ResponseEntity<PropertyView> property(UUID id, String ontologyId) {
        return ResponseEntity.ok(modelingService.property(ontologyId, id));
    }

    @Override
    public ResponseEntity<List<SearchResult>> searchModelingResources(String ontologyId, String query) {
        return ResponseEntity.ok(modelingService.search(ontologyId, query));
    }

    @Override
    public ResponseEntity<ModelingSummary> getModelingSummary(String ontologyId) {
        return ResponseEntity.ok(modelingService.summary(ontologyId));
    }

    @Override
    public ResponseEntity<FunctionExecution> testFunction(
            String id, String ontologyId, FunctionTestRequest functionTestRequest) {
        return ResponseEntity.ok(
                modelingService.testFunction(ontologyId, id, functionTestRequest));
    }

    @Override
    public ResponseEntity<ResourceView> updateAction(
            String id,
            String ontologyId,
            ResourceIdentityRequest resourceIdentityRequest) {
        return current(modelingService.updateIdentity(
                ontologyId, id, ACTION, resourceIdentityRequest));
    }

    @Override
    public ResponseEntity<ResourceView> updateFunction(
            String id,
            String ontologyId,
            ResourceIdentityRequest resourceIdentityRequest) {
        return current(modelingService.updateIdentity(
                ontologyId, id, FUNCTION, resourceIdentityRequest));
    }

    @Override
    public ResponseEntity<ResourceView> updateInterface(
            String id,
            String ontologyId,
            ResourceIdentityRequest resourceIdentityRequest) {
        return current(modelingService.updateIdentity(
                ontologyId, id, INTERFACE, resourceIdentityRequest));
    }

    @Override
    public ResponseEntity<ResourceView> updateLinkType(
            String id,
            String ontologyId,
            ResourceIdentityRequest resourceIdentityRequest) {
        return current(modelingService.updateIdentity(
                ontologyId, id, LINK_TYPE, resourceIdentityRequest));
    }

    private ResponseEntity<ResourceView> created(
            VersionedResource value, String ontologyId, String segment) {
        URI location = URI.create("/v1/ontologies/" + ontologyId + "/" + segment + "/"
                + value.resource().getId());
        return ResponseEntity.created(location)
                .eTag(Long.toString(value.etag()))
                .body(value.resource());
    }

    private ResponseEntity<ResourceView> current(VersionedResource value) {
        return ResponseEntity.ok()
                .eTag(Long.toString(value.etag()))
                .body(value.resource());
    }

    private ResponseEntity<Void> delete(
            String ontologyId, String id, ResourceKind kind) {
        modelingService.delete(ontologyId, id, kind);
        return ResponseEntity.noContent().build();
    }
}
