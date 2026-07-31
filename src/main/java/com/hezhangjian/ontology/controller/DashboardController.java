package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.DashboardApi;
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
import com.hezhangjian.ontology.service.DashboardApiService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class DashboardController implements DashboardApi {
    private final DashboardApiService dashboardService;

    @Override
    public ResponseEntity<DashboardDetail> archiveDashboard(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.archive(ontologyId, id));
    }

    @Override
    public ResponseEntity<DashboardDetail> copy(UUID id, String ontologyId) {
        DashboardDetail value = dashboardService.copy(ontologyId, id);
        return ResponseEntity.created(location(ontologyId, value)).body(value);
    }

    @Override
    public ResponseEntity<DashboardDetail> createDashboard(
            String ontologyId, DashboardCreateRequest request) {
        DashboardDetail value = dashboardService.create(ontologyId, request);
        return ResponseEntity.created(location(ontologyId, value)).body(value);
    }

    @Override
    public ResponseEntity<Void> deleteDashboard(UUID id, String ontologyId) {
        dashboardService.delete(ontologyId, id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DashboardDraftView> getDashboardDraft(UUID id, String ontologyId) {
        DashboardApiService.Versioned<DashboardDraftView> value =
                dashboardService.draft(ontologyId, id);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<DrilldownToken> drilldown(
            String ontologyId, UUID planId, DrilldownRequest request) {
        return ResponseEntity.ok(dashboardService.drilldown(ontologyId, planId, request));
    }

    @Override
    public ResponseEntity<DashboardBatchResult> execute(
            String ontologyId, UUID planId, DashboardExecuteRequest request) {
        return ResponseEntity.accepted()
                .body(dashboardService.execute(ontologyId, planId, request, false));
    }

    @Override
    public ResponseEntity<DashboardBatchResult> executeBatch(
            String ontologyId, UUID planId, DashboardExecuteRequest request) {
        return ResponseEntity.accepted()
                .body(dashboardService.execute(ontologyId, planId, request, true));
    }

    @Override
    public ResponseEntity<Void> favorite(UUID id, String ontologyId) {
        dashboardService.favorite(ontologyId, id, true);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<Map<String, Object>>> filterOptions(
            String ontologyId, UUID planId, FilterOptionsRequest request) {
        return ResponseEntity.ok(
                dashboardService.filterOptions(ontologyId, planId, request));
    }

    @Override
    public ResponseEntity<DashboardDetail> getDashboard(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.get(ontologyId, id));
    }

    @Override
    public ResponseEntity<DashboardHealth> getDashboardHealth(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.health(ontologyId, id));
    }

    @Override
    public ResponseEntity<List<DashboardSummary>> listDashboards(
            String ontologyId, String keyword, String lifecycle, Boolean favorites) {
        return ResponseEntity.ok(
                dashboardService.list(ontologyId, keyword, lifecycle, favorites));
    }

    @Override
    public ResponseEntity<DashboardEditLock> lock(
            UUID id, String ontologyId, EditLockRequest request) {
        return ResponseEntity.ok(dashboardService.lock(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<DashboardDetail> patch(
            String ifMatch, UUID id, String ontologyId, DashboardPatchRequest request) {
        return ResponseEntity.ok(
                dashboardService.patch(ontologyId, id, ifMatch, request));
    }

    @Override
    public ResponseEntity<List<DashboardPermission>> listDashboardPermissions(
            UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.permissions(ontologyId, id));
    }

    @Override
    public ResponseEntity<List<DashboardPermission>> replaceDashboardPermissions(
            UUID id, String ontologyId, DashboardPermissionsRequest request) {
        return ResponseEntity.ok(
                dashboardService.putPermissions(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<DashboardQueryPlanView> plan(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.plan(ontologyId, id));
    }

    @Override
    public ResponseEntity<DashboardDetail> publishDashboard(
            UUID id, String ontologyId, PublishRequest request) {
        return ResponseEntity.ok(dashboardService.publish(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<DashboardDraftView> putDraft(
            String ifMatch, UUID id, String ontologyId, DashboardDefinition request) {
        DashboardApiService.Versioned<DashboardDraftView> value =
                dashboardService.putDraft(ontologyId, id, ifMatch, request);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<Void> release(UUID id, String ontologyId) {
        dashboardService.release(ontologyId, id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DashboardEditLock> renew(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.renew(ontologyId, id));
    }

    @Override
    public ResponseEntity<DashboardDetail> restore(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.restore(ontologyId, id));
    }

    @Override
    public ResponseEntity<Void> unfavorite(UUID id, String ontologyId) {
        dashboardService.favorite(ontologyId, id, false);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DashboardUsage> getDashboardUsage(UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.usage(ontologyId, id));
    }

    @Override
    public ResponseEntity<DashboardValidationResult> validateDashboard(
            UUID id, String ontologyId) {
        return ResponseEntity.ok(dashboardService.validate(ontologyId, id));
    }

    private URI location(String ontologyId, DashboardDetail value) {
        return URI.create("/v1/ontologies/" + ontologyId
                + "/dashboards/"
                + Objects.requireNonNull(value.getSummary(), "Dashboard summary is required").getId());
    }
}
