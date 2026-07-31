package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.DatasetApi;
import com.hezhangjian.ontology.model.CreateDatasetReq;
import com.hezhangjian.ontology.model.Dataset;
import com.hezhangjian.ontology.model.DatasetMappingPreview;
import com.hezhangjian.ontology.model.DatasetPage;
import com.hezhangjian.ontology.model.DatasetPreview;
import com.hezhangjian.ontology.model.DatasetQueryReq;
import com.hezhangjian.ontology.model.DatasetQueryResult;
import com.hezhangjian.ontology.model.MaterializeRequest;
import com.hezhangjian.ontology.model.UpdateDatasetReq;
import com.hezhangjian.ontology.service.DatasetApiService;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class DatasetController implements DatasetApi {
    private final DatasetApiService datasetService;

    @Override
    public ResponseEntity<Dataset> createDataset(
            String ontologyId, CreateDatasetReq createDatasetReq) {
        Dataset dataset = datasetService.create(ontologyId, createDatasetReq);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(datasetService.etag(dataset))
                .body(dataset);
    }

    @Override
    public ResponseEntity<Void> deleteDataset(String datasetId, String ontologyId) {
        datasetService.delete(ontologyId, datasetId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Dataset> getDataset(String datasetId, String ontologyId) {
        Dataset dataset = datasetService.get(ontologyId, datasetId);
        return ResponseEntity.ok().eTag(datasetService.etag(dataset)).body(dataset);
    }

    @Override
    public ResponseEntity<DatasetPage> listDatasets(String ontologyId, String search) {
        return ResponseEntity.ok(datasetService.list(ontologyId, search));
    }

    @Override
    public ResponseEntity<Dataset> materialize(
            String ontologyId, UUID pipelineId, MaterializeRequest materializeRequest) {
        Dataset dataset = datasetService.materialize(ontologyId, pipelineId, materializeRequest);
        URI location =
                URI.create("/v1/ontologies/" + ontologyId + "/datasets/" + dataset.getId());
        return ResponseEntity.created(location).body(dataset);
    }

    @Override
    public ResponseEntity<DatasetMappingPreview> previewDatasetMapping(
            String identityField, String titleField, String datasetId, String ontologyId) {
        return ResponseEntity.ok(datasetService.mappingPreview(
                ontologyId, datasetId, identityField, titleField));
    }

    @Override
    public ResponseEntity<DatasetPreview> previewDataset(
            String datasetId, String ontologyId, Integer limit, Integer offset) {
        return ResponseEntity.ok(datasetService.preview(ontologyId, datasetId, limit, offset));
    }

    @Override
    public ResponseEntity<DatasetQueryResult> queryDataset(
            String datasetId, String ontologyId, DatasetQueryReq datasetQueryReq) {
        return ResponseEntity.ok(datasetService.query(ontologyId, datasetId, datasetQueryReq));
    }

    @Override
    public ResponseEntity<Dataset> updateDataset(
            String ifMatch,
            String datasetId,
            String ontologyId,
            UpdateDatasetReq updateDatasetReq) {
        Dataset dataset =
                datasetService.update(ontologyId, datasetId, updateDatasetReq, ifMatch);
        return ResponseEntity.ok().eTag(datasetService.etag(dataset)).body(dataset);
    }
}
