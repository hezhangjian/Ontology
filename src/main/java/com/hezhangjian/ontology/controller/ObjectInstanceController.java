package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.ObjectInstanceApi;
import com.hezhangjian.ontology.model.BulkObjectInstanceReq;
import com.hezhangjian.ontology.model.BulkObjectInstanceResult;
import com.hezhangjian.ontology.model.CreateObjectInstanceImportReq;
import com.hezhangjian.ontology.model.CreateObjectInstanceReq;
import com.hezhangjian.ontology.model.ObjectInstance;
import com.hezhangjian.ontology.model.ObjectInstanceAggregateReq;
import com.hezhangjian.ontology.model.ObjectInstanceAggregateResult;
import com.hezhangjian.ontology.model.ObjectInstanceImportJob;
import com.hezhangjian.ontology.model.ObjectInstanceImportError;
import com.hezhangjian.ontology.model.ObjectInstancePage;
import com.hezhangjian.ontology.model.ObjectInstanceProjectionStatus;
import com.hezhangjian.ontology.model.ObjectInstanceQueryReq;
import com.hezhangjian.ontology.model.ObjectInstanceReconciliationJob;
import com.hezhangjian.ontology.model.ObjectInstanceReconciliationResult;
import com.hezhangjian.ontology.model.ReconcileObjectInstancesReq;
import com.hezhangjian.ontology.model.UpdateObjectInstanceReq;
import com.hezhangjian.ontology.service.ObjectInstanceApiService;
import com.hezhangjian.ontology.service.ObjectInstanceBulkService;
import com.hezhangjian.ontology.service.ObjectInstanceImportService;
import com.hezhangjian.ontology.service.ObjectInstanceReconciliationService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class ObjectInstanceController implements ObjectInstanceApi {
    private final ObjectInstanceApiService instances;
    private final ObjectInstanceBulkService bulkMutations;
    private final ObjectInstanceImportService imports;
    private final ObjectInstanceReconciliationService reconciliations;

    @Override
    public ResponseEntity<ObjectInstance> createObjectInstance(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            CreateObjectInstanceReq request) {
        ObjectInstance value = instances.create(
                ontologyId, objectTypeId, request.getProperties(), idempotencyKey);
        URI location = URI.create("/v1/ontologies/" + ontologyId + "/object-types/"
                + objectTypeId + "/object-instances/" + value.getId());
        return ResponseEntity.created(location)
                .eTag(Long.toString(value.getVersion()))
                .body(value);
    }

    @Override
    public ResponseEntity<Void> deleteObjectInstance(
            String ifMatch, String objectId, String objectTypeId, String ontologyId) {
        instances.delete(ontologyId, objectTypeId, objectId, ifMatch);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ObjectInstance> getObjectInstance(
            String objectId, String objectTypeId, String ontologyId) {
        ObjectInstance value = instances.get(ontologyId, objectTypeId, objectId);
        return ResponseEntity.ok()
                .eTag(Long.toString(value.getVersion()))
                .body(value);
    }

    @Override
    public ResponseEntity<ObjectInstancePage> listObjectInstances(
            String objectTypeId,
            String ontologyId,
            Integer pageSize,
            String cursor) {
        return ResponseEntity.ok(instances.list(
                ontologyId, objectTypeId, pageSize, cursor));
    }

    @Override
    public ResponseEntity<ObjectInstancePage> queryObjectInstances(
            String objectTypeId,
            String ontologyId,
            ObjectInstanceQueryReq request) {
        return ResponseEntity.ok(instances.query(ontologyId, objectTypeId, request));
    }

    @Override
    public ResponseEntity<ObjectInstance> updateObjectInstance(
            String ifMatch,
            String objectId,
            String objectTypeId,
            String ontologyId,
            UpdateObjectInstanceReq request) {
        ObjectInstance value = instances.update(
                ontologyId, objectTypeId, objectId, ifMatch, request.getProperties());
        return ResponseEntity.ok()
                .eTag(Long.toString(value.getVersion()))
                .body(value);
    }

    @Override
    public ResponseEntity<ObjectInstanceProjectionStatus> getObjectInstanceProjectionStatus(
            String objectId, String objectTypeId, String ontologyId) {
        return ResponseEntity.ok(
                instances.projectionStatus(ontologyId, objectTypeId, objectId));
    }

    @Override
    public ResponseEntity<BulkObjectInstanceResult> bulkMutateObjectInstances(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            BulkObjectInstanceReq request) {
        return ResponseEntity.ok(bulkMutations.mutate(
                idempotencyKey, objectTypeId, ontologyId, request));
    }

    @Override
    public ResponseEntity<ObjectInstanceAggregateResult> aggregateObjectInstances(
            String objectTypeId,
            String ontologyId,
            ObjectInstanceAggregateReq request) {
        return ResponseEntity.ok(instances.aggregate(ontologyId, objectTypeId, request));
    }

    @Override
    public ResponseEntity<ObjectInstanceImportJob> createObjectInstanceImport(
            String objectTypeId,
            String ontologyId,
            CreateObjectInstanceImportReq request) {
        return ResponseEntity.accepted()
                .body(imports.create(ontologyId, objectTypeId, request));
    }

    @Override
    public ResponseEntity<ObjectInstanceImportJob> getObjectInstanceImport(
            UUID jobId, String objectTypeId, String ontologyId) {
        return ResponseEntity.ok(imports.get(ontologyId, objectTypeId, jobId));
    }

    @Override
    public ResponseEntity<ObjectInstanceImportJob> cancelObjectInstanceImport(
            UUID jobId, String objectTypeId, String ontologyId) {
        return ResponseEntity.accepted()
                .body(imports.cancel(ontologyId, objectTypeId, jobId));
    }

    @Override
    public ResponseEntity<List<ObjectInstanceImportError>> listObjectInstanceImportErrors(
            UUID jobId, String objectTypeId, String ontologyId) {
        return ResponseEntity.ok(imports.errors(ontologyId, objectTypeId, jobId));
    }

    @Override
    public ResponseEntity<ObjectInstanceReconciliationJob> reconcileObjectInstances(
            String objectTypeId,
            String ontologyId,
            ReconcileObjectInstancesReq request) {
        return ResponseEntity.accepted()
                .body(reconciliations.create(
                        ontologyId,
                        objectTypeId,
                        request == null || request.getRepair() == null
                                || request.getRepair()));
    }

    @Override
    public ResponseEntity<ObjectInstanceReconciliationResult> getObjectInstanceReconciliation(
            UUID jobId, String objectTypeId, String ontologyId) {
        return ResponseEntity.ok(
                reconciliations.get(ontologyId, objectTypeId, jobId));
    }

}
