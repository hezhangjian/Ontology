package com.hezhangjian.ontology.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hezhangjian.ontology.api.DataSetApi;
import com.hezhangjian.ontology.entity.DataSetEntity;
import com.hezhangjian.ontology.model.CreateDataSetReq;
import com.hezhangjian.ontology.model.DataSet;
import com.hezhangjian.ontology.model.DataSetField;
import com.hezhangjian.ontology.model.DataSetPage;
import com.hezhangjian.ontology.model.UpdateDataSetReq;
import com.hezhangjian.ontology.module.OffsetPageRequest;
import com.hezhangjian.ontology.repo.DataSetRepository;
import com.hezhangjian.ontology.repo.OntologyRepository;
import com.hezhangjian.ontology.util.JacksonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
public class DataSetController implements DataSetApi {
    private static final TypeReference<List<DataSetField>> DATA_SET_FIELDS = new TypeReference<>() {};

    private final DataSetRepository dataSetRepository;
    private final OntologyRepository ontologyRepository;

    @Override
    public ResponseEntity<DataSet> createDataSet(String ontologyId, CreateDataSetReq createDataSetReq) {
        if (!ontologyRepository.existsById(ontologyId)) {
            return ResponseEntity.notFound().build();
        }
        if (dataSetRepository.existsByOntologyIdAndId(ontologyId, createDataSetReq.getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        DataSetEntity entity = new DataSetEntity();
        entity.setId(createDataSetReq.getId());
        entity.setOntologyId(ontologyId);
        entity.setName(createDataSetReq.getName());
        entity.setDescription(createDataSetReq.getDescription());
        entity.setFieldsJson(writeFields(createDataSetReq.getFields()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toModel(dataSetRepository.save(entity)));
    }

    @Override
    public ResponseEntity<Void> deleteDataSet(String ontologyId, String datasetId) {
        return dataSetRepository
                .findByOntologyIdAndId(ontologyId, datasetId)
                .map(entity -> {
                    dataSetRepository.delete(entity);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<DataSet> getDataSet(String ontologyId, String datasetId) {
        return dataSetRepository
                .findByOntologyIdAndId(ontologyId, datasetId)
                .map(entity -> ResponseEntity.ok(toModel(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<DataSetPage> listDataSets(String ontologyId, Integer limit, Integer offset) {
        if (!ontologyRepository.existsById(ontologyId)) {
            return ResponseEntity.notFound().build();
        }

        Page<DataSetEntity> page = dataSetRepository.findByOntologyId(
                ontologyId, OffsetPageRequest.of(limit, offset, Sort.by("id")));
        DataSetPage response = new DataSetPage()
                .items(page.getContent().stream().map(this::toModel).toList())
                .total(page.getTotalElements())
                .limit(limit)
                .offset(offset);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<DataSet> updateDataSet(String ontologyId, String datasetId, UpdateDataSetReq updateDataSetReq) {
        return dataSetRepository
                .findByOntologyIdAndId(ontologyId, datasetId)
                .map(entity -> updateExistingDataSet(ontologyId, datasetId, updateDataSetReq, entity))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<DataSet> updateExistingDataSet(
            String ontologyId, String datasetId, UpdateDataSetReq updateDataSetReq, DataSetEntity entity) {
        String nextId = updateDataSetReq.getId();
        if (nextId != null && !nextId.equals(datasetId)) {
            if (dataSetRepository.existsByOntologyIdAndId(ontologyId, nextId)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            DataSetEntity replacement = new DataSetEntity();
            replacement.setId(nextId);
            replacement.setOntologyId(entity.getOntologyId());
            replacement.setName(entity.getName());
            replacement.setDescription(entity.getDescription());
            replacement.setFieldsJson(entity.getFieldsJson());
            replacement.setCreatedAt(entity.getCreatedAt());
            dataSetRepository.delete(entity);
            entity = replacement;
        }
        if (updateDataSetReq.getName() != null) {
            entity.setName(updateDataSetReq.getName());
        }
        if (updateDataSetReq.getDescription() != null) {
            entity.setDescription(updateDataSetReq.getDescription());
        }
        if (updateDataSetReq.getFields() != null) {
            entity.setFieldsJson(writeFields(updateDataSetReq.getFields()));
        }
        return ResponseEntity.ok(toModel(dataSetRepository.save(entity)));
    }

    private DataSet toModel(DataSetEntity entity) {
        return new DataSet()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .fields(readFields(entity.getFieldsJson()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt());
    }

    private List<DataSetField> readFields(String fieldsJson) {
        try {
            return JacksonUtil.toList(fieldsJson, DATA_SET_FIELDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to read DataSet fields", e);
        }
    }

    private String writeFields(List<DataSetField> fields) {
        try {
            return JacksonUtil.toJson(fields == null ? List.of() : fields);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to write DataSet fields", e);
        }
    }
}
