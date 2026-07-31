package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.exception.ConnectionProblem;
import com.hezhangjian.ontology.security.WorkspaceContext;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@RequiredArgsConstructor
@Service
public class DataConnectionApiService {
    private final OntologyLookupService catalogs;
    private final ObjectMapper objectMapper;
    private final DataConnectionService connections;

    public DataSourcePage list(String ontologyApiName, Integer page, Integer size,
            String search, String type, String status, String owner) {
        return inOntology(ontologyApiName, () -> convert(
                connections.list(page, size, search, type, status, owner),
                DataSourcePage.class));
    }

    public TestResult test(String ontologyApiName, TestRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                connections.test(
                        request(request, ConnectionModels.TestRequest.class), actor()),
                TestResult.class));
    }

    public Versioned<DataSource> create(String ontologyApiName, CreateRequest request) {
        return inOntology(ontologyApiName, () -> versioned(connections.create(
                request(request, ConnectionModels.CreateRequest.class), actor())));
    }

    public Versioned<DataSource> importLocalCsv(
            String ontologyApiName, String name, String description, List<String> tags,
            List<MultipartFile> files) {
        return inOntology(ontologyApiName, () -> versioned(
                connections.importLocalCsv(name, description, tags, files, actor())));
    }

    public Versioned<DataSource> get(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () -> versioned(connections.get(id)));
    }

    public Overview overview(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(connections.overview(id), Overview.class));
    }

    public Versioned<DataSource> update(
            String ontologyApiName, UUID id, String ifMatch, UpdateRequest request) {
        return inOntology(ontologyApiName, () -> versioned(connections.update(
                id,
                parseEtag(ifMatch),
                request(request, ConnectionModels.UpdateRequest.class),
                actor())));
    }

    public TestResult retest(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(connections.retest(id, actor()), TestResult.class));
    }

    public DataSource disable(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(connections.disable(id, actor()), DataSource.class));
    }

    public DataSource enable(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(connections.enable(id, actor()), DataSource.class));
    }

    public void delete(String ontologyApiName, UUID id) {
        inOntology(ontologyApiName, () -> {
            connections.delete(id, actor());
            return null;
        });
    }

    public CredentialSummary rotate(
            String ontologyApiName, UUID id, RotateCredentialRequest request) {
        return inOntology(ontologyApiName, () -> convert(
                connections.rotate(
                        id,
                        request(request.getCredential(), ConnectionModels.CredentialInput.class),
                        actor()),
                CredentialSummary.class));
    }

    public List<CredentialSummary> credentials(String ontologyApiName) {
        return inOntology(ontologyApiName, () ->
                convertList(connections.credentials(actor()), CredentialSummary.class));
    }

    public AssetPage assets(
            String ontologyApiName, UUID id, Integer page, Integer size, String search) {
        return inOntology(ontologyApiName, () ->
                convert(connections.assets(id, page, size, search), AssetPage.class));
    }

    public DiscoveryRun discover(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convert(connections.discover(id, actor()), DiscoveryRun.class));
    }

    public DataSourceAsset asset(String ontologyApiName, UUID id, UUID assetId) {
        return inOntology(ontologyApiName, () ->
                convert(connections.asset(id, assetId), DataSourceAsset.class));
    }

    public DiscoveryRun inferSchema(
            String ontologyApiName, UUID id, UUID assetId) {
        return inOntology(ontologyApiName, () -> convert(
                connections.inferSchema(id, assetId, actor()), DiscoveryRun.class));
    }

    public AssetPreview preview(
            String ontologyApiName, UUID id, UUID assetId, PreviewRequest request) {
        Integer requestedLimit = request == null ? null : request.getLimit();
        int limit = requestedLimit == null ? 50 : requestedLimit;
        return inOntology(ontologyApiName, () -> convert(
                connections.preview(id, assetId, limit, actor()), AssetPreview.class));
    }

    public AssetUsage usage(String ontologyApiName, UUID id, UUID assetId) {
        return inOntology(ontologyApiName, () ->
                convert(connections.usage(id, assetId), AssetUsage.class));
    }

    public List<PipelineSummary> pipelines(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convertList(connections.pipelines(id), PipelineSummary.class));
    }

    public List<PipelineRunSummary> runs(String ontologyApiName, UUID id) {
        return inOntology(ontologyApiName, () ->
                convertList(connections.runs(id), PipelineRunSummary.class));
    }

    private Versioned<DataSource> versioned(ConnectionModels.DataSource source) {
        return new Versioned<>(
                convert(source, DataSource.class), Long.toString(source.version()));
    }

    private ConnectionModels.Actor actor() {
        return new ConnectionModels.Actor("local", "Local", true);
    }

    private <T> T inOntology(String apiName, Supplier<T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, work);
    }

    private long parseEtag(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.replace("W/", "").replace("\"", "").trim());
        } catch (NumberFormatException cause) {
            throw new ConnectionProblem(
                    "ETAG_INVALID", "If-Match ETag 格式无效");
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
