package com.hezhangjian.ontology.service;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.security.WorkspaceContext;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Service
public class ModelingApiService {
    private final OntologyLookupService catalogs;
    private final ObjectMapper objectMapper;
    private final ModelingService modeling;

    public ModelingSummary summary(String ontologyApiName) {
        return inOntology(ontologyApiName, ontologyId ->
                convert(modeling.summary(ontologyId), ModelingSummary.class));
    }

    public List<SearchResult> search(String ontologyApiName, String query) {
        return inOntology(ontologyApiName, ontologyId ->
                convertList(modeling.search(ontologyId, query), SearchResult.class));
    }

    public List<ResourceView> list(
            String ontologyApiName, ResourceKind kind, String search) {
        return inOntology(ontologyApiName, ontologyId ->
                convertList(modeling.list(ontologyId, kind, search), ResourceView.class));
    }

    public VersionedResource get(
            String ontologyApiName, String apiName, ResourceKind kind) {
        return inOntology(ontologyApiName, ontologyId ->
                versioned(requireKind(resourceId(ontologyId, apiName, kind), kind)));
    }

    public VersionedResource create(
            String ontologyApiName, ResourceKind kind, ResourceDraftRequest request) {
        return inOntology(ontologyApiName, ontologyId -> versioned(modeling.create(
                ontologyId,
                kind,
                convert(request, ModelingModels.ResourceDraftRequest.class))));
    }

    public VersionedResource updateIdentity(
            String ontologyApiName,
            String apiName,
            ResourceKind kind,
            ResourceIdentityRequest request) {
        return inOntology(ontologyApiName, ontologyId -> {
            UUID id = resourceId(ontologyId, apiName, kind);
            return versioned(modeling.updateIdentity(
                    ontologyId,
                    id,
                    convert(request, ModelingModels.ResourceIdentityRequest.class)));
        });
    }

    public void delete(String ontologyApiName, String apiName, ResourceKind kind) {
        inOntology(ontologyApiName, ontologyId -> {
            UUID id = resourceId(ontologyId, apiName, kind);
            requireKind(id, kind);
            modeling.delete(id);
            return null;
        });
    }

    public ObjectTypeBackingView objectTypeBacking(
            String ontologyApiName, String apiName) {
        return inOntology(ontologyApiName, ontologyId -> {
            UUID id = resourceId(ontologyId, apiName, ResourceKind.OBJECT_TYPE);
            requireKind(id, ResourceKind.OBJECT_TYPE);
            return convert(
                    modeling.objectTypeBacking(ontologyId, id),
                    ObjectTypeBackingView.class);
        });
    }

    public VersionedResource createObjectDraft(
            String ontologyApiName,
            String apiName,
            ResourceDraftRequest request,
            String ifMatch) {
        return inOntology(ontologyApiName, ontologyId -> versioned(modeling.createObjectDraft(
                resourceId(ontologyId, apiName, ResourceKind.OBJECT_TYPE),
                convert(request, ModelingModels.ResourceDraftRequest.class),
                parseEtag(ifMatch))));
    }

    public List<PropertyView> properties(String ontologyApiName, UUID objectTypeId) {
        return inOntology(ontologyApiName, ontologyId -> convertList(
                modeling.propertiesForOntology(ontologyId, objectTypeId),
                PropertyView.class));
    }

    public PropertyView property(String ontologyApiName, UUID propertyId) {
        return inOntology(ontologyApiName, ontologyId ->
                modeling.propertiesForOntology(ontologyId, null).stream()
                .filter(property -> property.id().equals(propertyId))
                .findFirst()
                .map(property -> convert(property, PropertyView.class))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Property not found")));
    }

    public ActionPreview previewAction(
            String ontologyApiName, String apiName, ActionPreviewRequest request) {
        return inOntology(ontologyApiName, ontologyId -> convert(
                modeling.previewAction(
                        resourceId(ontologyId, apiName, ResourceKind.ACTION),
                        convert(request, ModelingModels.ActionPreviewRequest.class)),
                ActionPreview.class));
    }

    public ActionExecution executeAction(
            String ontologyApiName,
            String apiName,
            ActionExecuteRequest request,
            String idempotencyKey) {
        return inOntology(ontologyApiName, ontologyId -> convert(
                modeling.executeAction(
                        resourceId(ontologyId, apiName, ResourceKind.ACTION),
                        new ModelingModels.ActionExecuteRequest(
                                request.getPreviewToken(), idempotencyKey)),
                ActionExecution.class));
    }

    public ActionExecution actionExecution(String ontologyApiName, UUID executionId) {
        return inOntology(ontologyApiName, ontologyId ->
                convert(modeling.actionExecution(executionId), ActionExecution.class));
    }

    public List<ActionExecution> actionExecutions(
            String ontologyApiName, String status) {
        return inOntology(ontologyApiName, ontologyId -> convertList(
                modeling.actionExecutions(status), ActionExecution.class));
    }

    public VersionedResource createFunctionDraft(
            String ontologyApiName,
            String apiName,
            ResourceDraftRequest request,
            long ifMatch) {
        return inOntology(ontologyApiName, ontologyId -> versioned(modeling.createFunctionDraft(
                resourceId(ontologyId, apiName, ResourceKind.FUNCTION),
                convert(request, ModelingModels.ResourceDraftRequest.class),
                ifMatch)));
    }

    public FunctionExecution testFunction(
            String ontologyApiName, String apiName, FunctionTestRequest request) {
        return inOntology(ontologyApiName, ontologyId -> convert(
                modeling.testFunction(
                        resourceId(ontologyId, apiName, ResourceKind.FUNCTION),
                        convert(request, ModelingModels.FunctionTestRequest.class)),
                FunctionExecution.class));
    }

    public List<HealthIssue> health(String ontologyApiName) {
        return inOntology(ontologyApiName, ontologyId ->
                convertList(modeling.health(), HealthIssue.class));
    }

    private ModelingModels.ResourceView requireKind(UUID id, ResourceKind kind) {
        ModelingModels.ResourceView resource = modeling.get(id);
        if (resource.kind() != kind) {
            throw new ResponseStatusException(NOT_FOUND, "Ontology resource not found");
        }
        return resource;
    }

    private UUID resourceId(UUID ontologyId, String apiName, ResourceKind kind) {
        return modeling.resolveResource(ontologyId, kind, apiName);
    }

    private VersionedResource versioned(ModelingModels.ResourceView resource) {
        ResourceView model = convert(resource, ResourceView.class);
        if (resource.kind() == ResourceKind.OBJECT_TYPE && resource.properties() != null) {
            resource.properties().stream()
                    .filter(ModelingModels.PropertyView::primaryKey)
                    .findFirst()
                    .ifPresent(property -> model.setPrimaryKeyPropertyId(property.id()));
            UUID primary = model.getPrimaryKeyPropertyId();
            resource.properties().stream()
                    .filter(ModelingModels.PropertyView::titleProperty)
                    .findFirst()
                    .map(ModelingModels.PropertyView::id)
                    .or(() -> java.util.Optional.ofNullable(primary))
                    .ifPresent(model::setTitlePropertyId);
            Object sourceMode = resource.definition().get("sourceMode");
            if (sourceMode != null) {
                model.setSourceMode(String.valueOf(sourceMode));
            }
            Object datasetId = resource.definition().get("datasetId");
            if (datasetId != null) {
                model.setDatasetId(String.valueOf(datasetId));
            }
            Object mapping = resource.definition().get("datasetMapping");
            if (mapping != null) {
                model.setDatasetMapping(objectMapper.convertValue(
                        mapping,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, String>>() {}));
            }
        }
        return new VersionedResource(model, resource.etag());
    }

    private long parseEtag(String value) {
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(
                    BAD_REQUEST, "If-Match must contain the current resource ETag", failure);
        }
    }

    private <T> T inOntology(String apiName, Function<UUID, T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, () -> work.apply(ontologyId));
    }

    private <T> T convert(Object source, Class<T> type) {
        if (source == null) {
            return null;
        }
        return objectMapper.convertValue(source, type);
    }

    private <T> List<T> convertList(List<?> source, Class<T> type) {
        return source.stream().map(value -> convert(value, type)).toList();
    }

    public record VersionedResource(ResourceView resource, long etag) {}
}
