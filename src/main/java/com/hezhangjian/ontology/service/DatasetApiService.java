package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.model.Dataset.StatusEnum;
import static com.hezhangjian.ontology.model.DatasetField.TypeEnum;
import static com.hezhangjian.ontology.model.DatasetSource.KindEnum;
import static org.springframework.http.HttpStatus.CONFLICT;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.service.DatasetModels.CreateDatasetRequest;
import com.hezhangjian.ontology.service.DatasetModels.Field;
import com.hezhangjian.ontology.service.DatasetModels.UpdateDatasetRequest;
import com.hezhangjian.ontology.service.PipelineModels.Actor;
import com.hezhangjian.ontology.security.WorkspaceContext;
import com.hezhangjian.ontology.model.CreateDatasetReq;
import com.hezhangjian.ontology.model.Dataset;
import com.hezhangjian.ontology.model.DatasetField;
import com.hezhangjian.ontology.model.DatasetPage;
import com.hezhangjian.ontology.model.DatasetSource;
import com.hezhangjian.ontology.model.DatasetMappingPreview;
import com.hezhangjian.ontology.model.MaterializeRequest;
import com.hezhangjian.ontology.model.DatasetPreview;
import com.hezhangjian.ontology.model.DatasetQueryReq;
import com.hezhangjian.ontology.model.DatasetQueryResult;
import com.hezhangjian.ontology.model.UpdateDatasetReq;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DatasetApiService {
    private static final TypeReference<Map<String, Object>> ROW_TYPE = new TypeReference<>() {};

    private final OntologyLookupService catalogs;
    private final DatasetService datasets;
    private final ObjectMapper objectMapper;

    public DatasetApiService(
            OntologyLookupService catalogs, DatasetService datasets, ObjectMapper objectMapper) {
        this.catalogs = catalogs;
        this.datasets = datasets;
        this.objectMapper = objectMapper;
    }

    public DatasetPage list(String ontologyApiName, String search) {
        return inOntology(ontologyApiName, () -> {
            com.hezhangjian.ontology.service.DatasetModels.DatasetPage page =
                    datasets.list(search);
            return new DatasetPage()
                    .items(page.items().stream().map(this::toModel).toList())
                    .total(page.total());
        });
    }

    public Dataset get(String ontologyApiName, String datasetApiName) {
        return inOntology(ontologyApiName, () -> toModel(datasets.get(datasetApiName)));
    }

    public Dataset create(String ontologyApiName, CreateDatasetReq request) {
        return inOntology(ontologyApiName, () -> {
            CreateDatasetRequest command = new CreateDatasetRequest(
                    request.getId(),
                    request.getName(),
                    request.getDescription(),
                    toFields(request.getFields()),
                    toRows(request.getRows()));
            return toModel(datasets.create(command, actor()));
        });
    }

    public Dataset update(
            String ontologyApiName,
            String datasetApiName,
            UpdateDatasetReq request,
            String ifMatch) {
        return inOntology(ontologyApiName, () -> {
            com.hezhangjian.ontology.service.DatasetModels.Dataset current =
                    datasets.get(datasetApiName);
            requireCurrentEtag(current, ifMatch);
            UpdateDatasetRequest command = new UpdateDatasetRequest(
                    request.getId(),
                    request.getName(),
                    request.getDescription(),
                    request.getFields() == null ? null : toFields(request.getFields()),
                    request.getRows() == null ? null : toRows(request.getRows()));
            return toModel(datasets.update(datasetApiName, command));
        });
    }

    public void delete(String ontologyApiName, String datasetApiName) {
        inOntology(ontologyApiName, () -> {
            datasets.delete(datasetApiName);
            return null;
        });
    }

    public DatasetPreview preview(
            String ontologyApiName, String datasetApiName, int limit, int offset) {
        return inOntology(ontologyApiName, () -> objectMapper.convertValue(
                datasets.preview(datasets.get(datasetApiName).internalId(), limit, offset),
                DatasetPreview.class));
    }

    public DatasetQueryResult query(
            String ontologyApiName, String datasetApiName, DatasetQueryReq request) {
        return inOntology(ontologyApiName, () -> objectMapper.convertValue(
                datasets.query(
                        datasetApiName,
                        objectMapper.convertValue(
                                request,
                                com.hezhangjian.ontology.service.DatasetModels.QueryRequest.class)),
                DatasetQueryResult.class));
    }

    public DatasetMappingPreview mappingPreview(
            String ontologyApiName,
            String datasetApiName,
            String identityField,
            String titleField) {
        return inOntology(ontologyApiName, () -> objectMapper.convertValue(
                datasets.mappingPreview(datasetApiName, identityField, titleField),
                DatasetMappingPreview.class));
    }

    public Dataset materialize(
            String ontologyApiName, UUID pipelineId, MaterializeRequest request) {
        return inOntology(ontologyApiName, () -> toModel(datasets.materialize(
                pipelineId,
                request == null
                        ? null
                        : objectMapper.convertValue(
                                request,
                                com.hezhangjian.ontology.service.DatasetModels
                                        .MaterializeRequest.class),
                actor())));
    }

    public String etag(Dataset dataset) {
        return '"' + dataset.getUpdatedAt().toInstant().toString() + '"';
    }

    private <T> T inOntology(String apiName, java.util.function.Supplier<T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, work);
    }

    private List<Field> toFields(List<DatasetField> fields) {
        if (fields == null) {
            return null;
        }
        return fields.stream()
                .map(field -> new Field(
                        field.getName(),
                        field.getType().getValue(),
                        field.getNullable(),
                        field.getSamples()))
                .toList();
    }

    private List<Map<String, Object>> toRows(List<?> rows) {
        if (rows == null) {
            return null;
        }
        return rows.stream()
                .map(row -> objectMapper.convertValue(row, ROW_TYPE))
                .toList();
    }

    private Actor actor() {
        return new Actor("local", "Local", true);
    }

    private void requireCurrentEtag(
            com.hezhangjian.ontology.service.DatasetModels.Dataset current,
            String ifMatch) {
        String normalized =
                ifMatch == null ? "" : ifMatch.replace("W/", "").replace("\"", "").trim();
        if (!current.updatedAt().toString().equals(normalized)) {
            throw new ResponseStatusException(CONFLICT, "Dataset has been modified");
        }
    }

    private Dataset toModel(
            com.hezhangjian.ontology.service.DatasetModels.Dataset source) {
        DatasetSource datasetSource = new DatasetSource()
                .kind(KindEnum.fromValue(source.source().kind()))
                .id(source.source().id())
                .name(source.source().name() == null ? "" : source.source().name());
        List<DatasetField> fields = source.fields().stream()
                .map(field -> new DatasetField()
                        .name(field.name())
                        .type(TypeEnum.fromValue(field.type()))
                        .nullable(field.nullable())
                        .samples(field.samples()))
                .toList();
        return new Dataset()
                .id(source.id())
                .name(source.name())
                .description(source.description())
                .source(datasetSource)
                .fields(fields)
                .rowCount(source.rowCount())
                .status(StatusEnum.fromValue(source.status()))
                .editable(source.editable())
                .ownerName(source.ownerName())
                .createdAt(source.createdAt().atOffset(ZoneOffset.UTC))
                .updatedAt(source.updatedAt().atOffset(ZoneOffset.UTC));
    }
}
