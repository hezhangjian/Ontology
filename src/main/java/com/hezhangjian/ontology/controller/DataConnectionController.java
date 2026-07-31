package com.hezhangjian.ontology.controller;

import com.hezhangjian.ontology.api.DataConnectionApi;
import com.hezhangjian.ontology.model.AssetPage;
import com.hezhangjian.ontology.model.AssetPreview;
import com.hezhangjian.ontology.model.AssetUsage;
import com.hezhangjian.ontology.model.CreateRequest;
import com.hezhangjian.ontology.model.CredentialSummary;
import com.hezhangjian.ontology.model.DataSource;
import com.hezhangjian.ontology.model.DataSourceAsset;
import com.hezhangjian.ontology.model.DataSourcePage;
import com.hezhangjian.ontology.model.DiscoveryRun;
import com.hezhangjian.ontology.model.Overview;
import com.hezhangjian.ontology.model.PipelineRunSummary;
import com.hezhangjian.ontology.model.PipelineSummary;
import com.hezhangjian.ontology.model.PreviewRequest;
import com.hezhangjian.ontology.model.RotateCredentialRequest;
import com.hezhangjian.ontology.model.TestRequest;
import com.hezhangjian.ontology.model.TestResult;
import com.hezhangjian.ontology.model.UpdateRequest;
import com.hezhangjian.ontology.service.DataConnectionApiService;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@RestController
public class DataConnectionController implements DataConnectionApi {
    private final DataConnectionApiService connectionService;

    @Override
    public ResponseEntity<DataSourceAsset> asset(
            UUID assetId, UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.asset(ontologyId, id, assetId));
    }

    @Override
    public ResponseEntity<AssetPage> listConnectionAssets(
            UUID id, String ontologyId, Integer page, Integer size, String search) {
        return ResponseEntity.ok(
                connectionService.assets(ontologyId, id, page, size, search));
    }

    @Override
    public ResponseEntity<DataSource> createConnection(
            String ontologyId, CreateRequest request) {
        return created(ontologyId, connectionService.create(ontologyId, request));
    }

    @Override
    public ResponseEntity<List<CredentialSummary>> listCredentials(
            String ontologyId, Boolean usable) {
        return ResponseEntity.ok(connectionService.credentials(ontologyId));
    }

    @Override
    public ResponseEntity<Void> deleteConnection(UUID id, String ontologyId) {
        connectionService.delete(ontologyId, id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<DataSource> disable(UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.disable(ontologyId, id));
    }

    @Override
    public ResponseEntity<DiscoveryRun> discover(UUID id, String ontologyId) {
        return ResponseEntity.accepted()
                .body(connectionService.discover(ontologyId, id));
    }

    @Override
    public ResponseEntity<DataSource> enable(UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.enable(ontologyId, id));
    }

    @Override
    public ResponseEntity<DataSource> getConnection(UUID id, String ontologyId) {
        DataConnectionApiService.Versioned<DataSource> value =
                connectionService.get(ontologyId, id);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<DataSource> importLocalCsv(
            String name, String ontologyId, List<MultipartFile> files,
            String description, List<String> tags) {
        return created(
                ontologyId,
                connectionService.importLocalCsv(
                        ontologyId, name, description, tags, files));
    }

    @Override
    public ResponseEntity<DiscoveryRun> inferSchema(
            UUID assetId, UUID id, String ontologyId) {
        return ResponseEntity.accepted()
                .body(connectionService.inferSchema(ontologyId, id, assetId));
    }

    @Override
    public ResponseEntity<DataSourcePage> listConnections(
            String ontologyId, Integer page, Integer size, String search,
            String type, String status, String owner) {
        return ResponseEntity.ok(connectionService.list(
                ontologyId, page, size, search, type, status, owner));
    }

    @Override
    public ResponseEntity<Overview> getConnectionOverview(UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.overview(ontologyId, id));
    }

    @Override
    public ResponseEntity<List<PipelineSummary>> listConnectionPipelines(
            UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.pipelines(ontologyId, id));
    }

    @Override
    public ResponseEntity<AssetPreview> previewConnectionAsset(
            UUID assetId, UUID id, String ontologyId, PreviewRequest request) {
        return ResponseEntity.ok(
                connectionService.preview(ontologyId, id, assetId, request));
    }

    @Override
    public ResponseEntity<TestResult> retest(UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.retest(ontologyId, id));
    }

    @Override
    public ResponseEntity<CredentialSummary> rotate(
            UUID id, String ontologyId, RotateCredentialRequest request) {
        return ResponseEntity.ok(connectionService.rotate(ontologyId, id, request));
    }

    @Override
    public ResponseEntity<List<PipelineRunSummary>> listConnectionRuns(
            UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.runs(ontologyId, id));
    }

    @Override
    public ResponseEntity<TestResult> test(
            String ontologyId, TestRequest request) {
        return ResponseEntity.ok(connectionService.test(ontologyId, request));
    }

    @Override
    public ResponseEntity<DataSource> updateConnection(
            String ifMatch, UUID id, String ontologyId, UpdateRequest request) {
        DataConnectionApiService.Versioned<DataSource> value =
                connectionService.update(ontologyId, id, ifMatch, request);
        return ResponseEntity.ok().eTag(value.etag()).body(value.value());
    }

    @Override
    public ResponseEntity<AssetUsage> getConnectionAssetUsage(
            UUID assetId, UUID id, String ontologyId) {
        return ResponseEntity.ok(connectionService.usage(ontologyId, id, assetId));
    }

    private ResponseEntity<DataSource> created(
            String ontologyId, DataConnectionApiService.Versioned<DataSource> value) {
        URI location = URI.create("/v1/ontologies/" + ontologyId
                + "/connections/" + value.value().getId());
        return ResponseEntity.created(location)
                .eTag(value.etag())
                .body(value.value());
    }
}
