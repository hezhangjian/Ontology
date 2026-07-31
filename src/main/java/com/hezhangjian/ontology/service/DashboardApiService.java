package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.security.WorkspaceContext;
import com.hezhangjian.ontology.model.DashboardBatchResult;
import com.hezhangjian.ontology.model.DashboardCreateRequest;
import com.hezhangjian.ontology.model.DashboardDefinition;
import com.hezhangjian.ontology.model.DashboardDetail;
import com.hezhangjian.ontology.model.DashboardDraftView;
import com.hezhangjian.ontology.model.DashboardEditLock;
import com.hezhangjian.ontology.model.DashboardExecuteRequest;
import com.hezhangjian.ontology.model.DashboardHealth;
import com.hezhangjian.ontology.model.DashboardPatchRequest;
import com.hezhangjian.ontology.model.DashboardPermission;
import com.hezhangjian.ontology.model.DashboardPermissionsRequest;
import com.hezhangjian.ontology.model.DashboardQueryPlanView;
import com.hezhangjian.ontology.model.DashboardSummary;
import com.hezhangjian.ontology.model.DashboardUsage;
import com.hezhangjian.ontology.model.DashboardValidationResult;
import com.hezhangjian.ontology.model.DrilldownRequest;
import com.hezhangjian.ontology.model.DrilldownToken;
import com.hezhangjian.ontology.model.EditLockRequest;
import com.hezhangjian.ontology.model.FilterOptionsRequest;
import com.hezhangjian.ontology.model.PublishRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RequiredArgsConstructor
@Service
public class DashboardApiService {
    private final OntologyLookupService catalogs;
    private final ObjectMapper objectMapper;
    private final DashboardService dashboards;

    public List<DashboardSummary> list(
            String ontologyApiName, String keyword, String lifecycle, Boolean favorites) {
        return inOntology(ontologyApiName, () -> convertList(
                dashboards.list(keyword, lifecycle, Boolean.TRUE.equals(favorites), actor()),
                DashboardSummary.class));
    }

    public DashboardDetail create(String ontologyApiName, DashboardCreateRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                dashboards.create(
                        request(request, DashboardModels.DashboardCreateRequest.class), actor()),
                DashboardDetail.class));
    }

    public DashboardDetail get(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.get(id, actor()), DashboardDetail.class));
    }

    public DashboardDetail patch(
            String ontologyApiName, UUID id, String ifMatch, DashboardPatchRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                dashboards.patch(
                        id,
                        etag(ifMatch),
                        request(request, DashboardModels.DashboardPatchRequest.class),
                        actor()),
                DashboardDetail.class));
    }

    public void delete(String ontologyApiName, UUID id) {
        inOntology(ontologyApiName, () -> {
            dashboards.delete(id, actor());
            return null;
        });
    }

    public DashboardDetail copy(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.copy(id, actor()), DashboardDetail.class));
    }

    public DashboardDetail archive(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.archive(id, actor()), DashboardDetail.class));
    }

    public DashboardDetail restore(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.restore(id, actor()), DashboardDetail.class));
    }

    public Versioned<DashboardDraftView> draft(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () -> {
            DashboardModels.DashboardDraftView value = dashboards.draft(id, actor());
            return new Versioned<>(
                    convert(value, DashboardDraftView.class), Long.toString(value.etag()));
        });
    }

    public Versioned<DashboardDraftView> putDraft(
            String ontologyApiName, UUID id, String ifMatch, DashboardDefinition request) {
        return inOntology(ontologyApiName, () -> {
            DashboardModels.DashboardDraftView value = dashboards.putDraft(
                    id,
                    etag(ifMatch),
                    request(request, DashboardModels.DashboardDefinition.class),
                    actor(),
                    true);
            return new Versioned<>(
                    convert(value, DashboardDraftView.class), Long.toString(value.etag()));
        });
    }

    public DashboardEditLock lock(
            String ontologyApiName, UUID id, EditLockRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                dashboards.acquireLock(
                        id,
                        request != null && Boolean.TRUE.equals(request.getForce()),
                        actor()),
                DashboardEditLock.class));
    }

    public DashboardEditLock renew(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.renewLock(id, actor()), DashboardEditLock.class));
    }

    public void release(String ontologyApiName, UUID id) {
        inOntology(ontologyApiName, () -> {
            dashboards.releaseLock(id, actor());
            return null;
        });
    }

    public DashboardValidationResult validate(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.validate(id, actor()), DashboardValidationResult.class));
    }

    public DashboardDetail publish(
            String ontologyApiName, UUID id, PublishRequest request) {
        return inOntology(ontologyApiName, () -> {
            DashboardModels.Actor actor = actor();
            dashboards.publish(
                    id, request(request, DashboardModels.PublishRequest.class), actor);
            return convert(dashboards.get(id, actor), DashboardDetail.class);
        });
    }

    public List<DashboardPermission> permissions(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () -> convertList(
                dashboards.permissions(id, actor()), DashboardPermission.class));
    }

    public List<DashboardPermission> putPermissions(
            String ontologyApiName, UUID id, DashboardPermissionsRequest request) {
        return inOntology(ontologyApiName, () -> convertList(
                dashboards.putPermissions(
                        id,
                        request(request, DashboardModels.DashboardPermissionsRequest.class),
                        actor()),
                DashboardPermission.class));
    }

    public void favorite(String ontologyApiName, UUID id, boolean value) {
        inOntology(ontologyApiName, () -> {
            dashboards.favorite(id, value, actor());
            return null;
        });
    }

    public DashboardHealth health(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.health(id, actor()), DashboardHealth.class));
    }

    public DashboardUsage usage(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.usage(id, actor()), DashboardUsage.class));
    }

    public DashboardQueryPlanView plan(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(dashboards.queryPlan(id, actor()), DashboardQueryPlanView.class));
    }

    public DashboardBatchResult execute(
            String ontologyApiName, UUID planId, DashboardExecuteRequest request, boolean batch) {
        return inOntology(ontologyApiName, () -> convert(
                batch
                        ? dashboards.execute(
                                planId,
                                request(request, DashboardModels.DashboardExecuteRequest.class),
                                actor())
                        : dashboards.executeSingle(
                                planId,
                                request(request, DashboardModels.DashboardExecuteRequest.class),
                                actor()),
                DashboardBatchResult.class));
    }

    public List<Map<String, Object>> filterOptions(
            String ontologyApiName, UUID planId, FilterOptionsRequest request) {
        return inOntology(ontologyApiName, () -> dashboards.filterOptions(
                planId,
                request(request, DashboardModels.FilterOptionsRequest.class),
                actor()));
    }

    public DrilldownToken drilldown(
            String ontologyApiName, UUID planId, DrilldownRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                dashboards.drilldown(
                        planId,
                        request(request, DashboardModels.DrilldownRequest.class),
                        actor()),
                DrilldownToken.class));
    }

    private DashboardModels.Actor actor() {
        return new DashboardModels.Actor("local", "Local", List.of("Admin", "Builder", "Viewer"));
    }

    private <T> T inOntology(String apiName, Supplier<T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, work);
    }

    private long etag(String value) {
        try {
            return Long.parseLong(value.replace("W/", "").replace("\"", "").trim());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "If-Match 必须包含有效 ETag", exception);
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
