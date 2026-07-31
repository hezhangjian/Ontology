package com.hezhangjian.ontology.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.client.DatasetStorageClient;
import com.hezhangjian.ontology.config.ObjectInstanceProperties;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.instance.ObjectInstanceService;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.model.CreateObjectInstanceImportReq;
import com.hezhangjian.ontology.model.ObjectInstanceImportError;
import com.hezhangjian.ontology.model.ObjectInstanceImportJob;
import com.hezhangjian.ontology.repo.SqlClientRepository;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectInstanceImportService {
    private static final TypeReference<Map<String, String>> MAPPING_TYPE =
            new TypeReference<>() {};

    private final OntologyLookupService catalogs;
    private final DatasetService datasets;
    private final DatasetStorageClient storage;
    private final ObjectInstanceRepository repository;
    private final ObjectInstanceService instances;
    private final SqlClientRepository jdbc;
    private final ObjectMapper json;
    private final DatasetObjectRowMapper rowMapper;
    private final int batchSize;

    public ObjectInstanceImportService(
            OntologyLookupService catalogs,
            DatasetService datasets,
            DatasetStorageClient storage,
            ObjectInstanceRepository repository,
            ObjectInstanceService instances,
            SqlClientRepository jdbc,
            ObjectMapper json,
            DatasetObjectRowMapper rowMapper,
            ObjectInstanceProperties properties) {
        this.catalogs = catalogs;
        this.datasets = datasets;
        this.storage = storage;
        this.repository = repository;
        this.instances = instances;
        this.jdbc = jdbc;
        this.json = json;
        this.rowMapper = rowMapper;
        this.batchSize = properties.importBatchSize();
    }

    @Transactional
    public ObjectInstanceImportJob create(
            String ontologyApiName,
            String objectTypeApiName,
            CreateObjectInstanceImportReq request) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        return WorkspaceContext.call(ontologyId, () -> {
            var dataset = datasets.get(request.getDatasetId());
            var schema = repository.schema(ontologyId, objectTypeApiName);
            rowMapper.validate(schema, request);
            UUID mappingId = jdbc.sql("""
                    SELECT id FROM control.dataset_object_mappings
                    WHERE dataset_id=:dataset AND object_type_id=:objectType
                    """).param("dataset", dataset.internalId())
                    .param("objectType", schema.objectTypeId())
                    .query(UUID.class)
                    .optional()
                    .orElse(UUID.randomUUID());
            String mode = request.getMode() == null
                    ? ("PIPELINE".equals(dataset.source().kind()) ? "REPLACE" : "UPSERT")
                    : request.getMode().getValue();
            jdbc.sql("""
                    INSERT INTO control.dataset_object_mappings(
                      id,ontology_id,dataset_id,object_type_id,identity_field,title_field,
                      field_mappings,default_mode)
                    VALUES (:id,:ontology,:dataset,:objectType,:identity,:title,
                      :mappings::jsonb,:mode)
                    ON CONFLICT(dataset_id,object_type_id) DO UPDATE
                    SET identity_field=excluded.identity_field,title_field=excluded.title_field,
                      field_mappings=excluded.field_mappings,default_mode=excluded.default_mode,
                      updated_at=now()
                    """).param("id", mappingId)
                    .param("ontology", ontologyId)
                    .param("dataset", dataset.internalId())
                    .param("objectType", schema.objectTypeId())
                    .param("identity", request.getIdentityField())
                    .param("title", request.getTitleField())
                    .param("mappings", write(request.getFieldMappings()))
                    .param("mode", mode)
                    .update();
            UUID jobId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO control.dataset_object_import_jobs(
                      id,ontology_id,mapping_id,dataset_id,object_type_id,mode)
                    VALUES (:id,:ontology,:mapping,:dataset,:objectType,:mode)
                    """).param("id", jobId)
                    .param("ontology", ontologyId)
                    .param("mapping", mappingId)
                    .param("dataset", dataset.internalId())
                    .param("objectType", schema.objectTypeId())
                    .param("mode", mode)
                    .update();
            return getInternal(jobId, ontologyId, schema.objectTypeId());
        });
    }

    public ObjectInstanceImportJob get(
            String ontologyApiName, String objectTypeApiName, UUID jobId) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        return WorkspaceContext.call(ontologyId, () -> getInternal(
                jobId,
                ontologyId,
                repository.schema(ontologyId, objectTypeApiName).objectTypeId()));
    }

    public List<ObjectInstanceImportError> errors(
            String ontologyApiName, String objectTypeApiName, UUID jobId) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        return WorkspaceContext.call(ontologyId, () -> {
            UUID objectTypeId =
                    repository.schema(ontologyId, objectTypeApiName).objectTypeId();
            getInternal(jobId, ontologyId, objectTypeId);
            return jdbc.sql("""
                    SELECT row_number,object_id,field_id,error_code,safe_message
                    FROM control.dataset_object_import_errors
                    WHERE job_id=:job ORDER BY row_number,error_code
                    """).param("job", jobId)
                    .query((row, number) -> new ObjectInstanceImportError()
                            .rowNumber(row.getLong("row_number"))
                            .objectId(row.getString("object_id"))
                            .fieldId(row.getString("field_id"))
                            .errorCode(row.getString("error_code"))
                            .safeMessage(row.getString("safe_message")))
                    .list();
        });
    }

    @Transactional
    public ObjectInstanceImportJob cancel(
            String ontologyApiName, String objectTypeApiName, UUID jobId) {
        UUID ontologyId = catalogs.resolve(ontologyApiName);
        return WorkspaceContext.call(ontologyId, () -> {
            UUID objectTypeId =
                    repository.schema(ontologyId, objectTypeApiName).objectTypeId();
            int changed = jdbc.sql("""
                    UPDATE control.dataset_object_import_jobs
                    SET cancel_requested=true,
                        status=CASE WHEN status IN ('QUEUED','VALIDATING')
                          THEN 'CANCELLED' ELSE status END,
                        completed_at=CASE WHEN status IN ('QUEUED','VALIDATING')
                          THEN now() ELSE completed_at END
                    WHERE id=:id AND ontology_id=:ontology AND object_type_id=:objectType
                      AND status NOT IN ('CANCELLED','COMPLETED','FAILED','PARTIAL')
                    """).param("id", jobId)
                    .param("ontology", ontologyId)
                    .param("objectType", objectTypeId)
                    .update();
            if (changed == 0) {
                getInternal(jobId, ontologyId, objectTypeId);
            }
            return getInternal(jobId, ontologyId, objectTypeId);
        });
    }

    @Scheduled(fixedDelay = 1_000, initialDelay = 3_000)
    void processQueued() {
        List<ImportWork> work = jdbc.sql("""
                SELECT j.id,j.ontology_id,j.dataset_id,j.object_type_id,j.mode,
                       m.identity_field,m.title_field,m.field_mappings::text,
                       o.api_name ontology_api,r.api_name object_type_api
                FROM control.dataset_object_import_jobs j
                JOIN control.dataset_object_mappings m ON m.id=j.mapping_id
                JOIN control.ontologies o ON o.id=j.ontology_id
                JOIN control.ontology_resources r ON r.id=j.object_type_id
                WHERE j.status='QUEUED' AND j.cancel_requested=false
                ORDER BY j.created_at
                LIMIT 1
                """).query((row, number) -> new ImportWork(
                row.getObject("id", UUID.class),
                row.getObject("ontology_id", UUID.class),
                row.getObject("dataset_id", UUID.class),
                row.getObject("object_type_id", UUID.class),
                row.getString("mode"),
                row.getString("identity_field"),
                row.getString("title_field"),
                readMapping(row.getString("field_mappings")),
                row.getString("ontology_api"),
                row.getString("object_type_api"))).list();
        work.forEach(this::process);
    }

    @Transactional
    public void enqueueConfigured(UUID datasetId) {
        jdbc.sql("""
                INSERT INTO control.dataset_object_import_jobs(
                  id,ontology_id,mapping_id,dataset_id,object_type_id,mode)
                SELECT gen_random_uuid(),m.ontology_id,m.id,m.dataset_id,m.object_type_id,
                       m.default_mode
                FROM control.dataset_object_mappings m
                WHERE m.dataset_id=:dataset
                  AND NOT EXISTS (
                    SELECT 1 FROM control.dataset_object_import_jobs j
                    WHERE j.mapping_id=m.id AND j.status IN (
                      'QUEUED','VALIDATING','MERGING'))
                """).param("dataset", datasetId).update();
    }

    private void process(ImportWork work) {
        if (jdbc.sql("""
                UPDATE control.dataset_object_import_jobs
                SET status='VALIDATING',started_at=now()
                WHERE id=:id AND status='QUEUED' AND cancel_requested=false
                """).param("id", work.id()).update() == 0) {
            return;
        }
        WorkspaceContext.run(work.ontologyId(), () -> {
            var schema = repository.schema(work.ontologyId(), work.objectTypeApiName());
            AtomicLong rowNumber = new AtomicLong();
            AtomicLong inserted = new AtomicLong();
            AtomicLong updated = new AtomicLong();
            AtomicLong unchanged = new AtomicLong();
            AtomicLong failed = new AtomicLong();
            try {
                jdbc.sql("""
                        DELETE FROM control.dataset_object_import_staging WHERE job_id=:job
                        """).param("job", work.id()).update();
                jdbc.sql("""
                        DELETE FROM control.dataset_object_import_errors WHERE job_id=:job
                        """).param("job", work.id()).update();
                storage.forEachPage(work.datasetId(), batchSize, page -> {
                    if (cancelRequested(work.id())) {
                        throw new ImportCancelled();
                    }
                    for (Map<String, Object> row : page) {
                        long number = rowNumber.incrementAndGet();
                        try {
                            Map<String, Object> properties = rowMapper.map(
                                    schema, work.fieldMappings(), row);
                            String objectId = String.valueOf(
                                    row.getOrDefault(work.identityField(), "")).trim();
                            if (objectId.isBlank()) {
                                throw new ObjectInstanceStoreException(
                                        "IMPORT_ID_EMPTY", "Identity field is empty");
                            }
                            String validatedId = instances.validateForImport(
                                    work.ontologyId(),
                                    work.objectTypeApiName(),
                                    properties);
                            if (!objectId.equals(validatedId)) {
                                throw new ObjectInstanceStoreException(
                                        "IMPORT_ID_MISMATCH",
                                        "Identity field and mapped primary key differ");
                            }
                            jdbc.sql("""
                                    INSERT INTO control.dataset_object_import_staging(
                                      job_id,row_number,object_id,payload)
                                    VALUES (:job,:rowNumber,:objectId,:payload::jsonb)
                                    """).param("job", work.id())
                                    .param("rowNumber", number)
                                    .param("objectId", objectId)
                                    .param("payload", write(properties))
                                    .update();
                        } catch (RuntimeException failure) {
                            failed.incrementAndGet();
                            error(work.id(), number, failure);
                        }
                    }
                    progress(work.id(), inserted, updated, unchanged, failed);
                });
                jdbc.sql("""
                        UPDATE control.dataset_object_import_jobs
                        SET status='MERGING' WHERE id=:id
                        """).param("id", work.id()).update();
                long cursor = 0;
                while (true) {
                    if (cancelRequested(work.id())) {
                        throw new ImportCancelled();
                    }
                    List<StagedRow> page = staged(work.id(), cursor);
                    if (page.isEmpty()) {
                        break;
                    }
                    for (StagedRow row : page) {
                        try {
                            var result = instances.mergeBase(
                                    work.ontologyId(),
                                    work.ontologyApiName(),
                                    work.objectTypeApiName(),
                                    row.properties(),
                                    "DATASET",
                                    work.datasetId().toString(),
                                    Long.toString(row.rowNumber()),
                                    work.id());
                            if (result.eventId() == null) {
                                unchanged.incrementAndGet();
                            } else if (result.instance().version() == 1) {
                                inserted.incrementAndGet();
                            } else {
                                updated.incrementAndGet();
                            }
                        } catch (RuntimeException failure) {
                            failed.incrementAndGet();
                            error(work.id(), row.rowNumber(), failure);
                        }
                    }
                    cursor = page.getLast().rowNumber();
                    progress(work.id(), inserted, updated, unchanged, failed);
                }
                long deleted = "REPLACE".equals(work.mode())
                        ? deleteMissing(work)
                        : 0;
                String status = failed.get() == 0 ? "COMPLETED" : "PARTIAL";
                jdbc.sql("""
                        UPDATE control.dataset_object_import_jobs
                        SET status=:status,inserted_count=:inserted,updated_count=:updated,
                            deleted_count=:deleted,unchanged_count=:unchanged,failed_count=:failed,
                            completed_at=now()
                        WHERE id=:id
                        """).param("status", status)
                        .param("inserted", inserted.get())
                        .param("updated", updated.get())
                        .param("deleted", deleted)
                        .param("unchanged", unchanged.get())
                        .param("failed", failed.get())
                        .param("id", work.id())
                        .update();
            } catch (ImportCancelled cancelled) {
                jdbc.sql("""
                        UPDATE control.dataset_object_import_jobs
                        SET status='CANCELLED',completed_at=now() WHERE id=:id
                        """).param("id", work.id()).update();
            } catch (RuntimeException failure) {
                jdbc.sql("""
                        UPDATE control.dataset_object_import_jobs
                        SET status='FAILED',safe_error=:error,completed_at=now()
                        WHERE id=:id
                        """).param("error", safeError(failure)).param("id", work.id()).update();
            }
        });
    }

    private List<StagedRow> staged(UUID jobId, long cursor) {
        return jdbc.sql("""
                SELECT row_number,payload::text
                FROM control.dataset_object_import_staging
                WHERE job_id=:job AND row_number>:cursor
                ORDER BY row_number LIMIT :limit
                """).param("job", jobId)
                .param("cursor", cursor)
                .param("limit", batchSize)
                .query((row, number) -> new StagedRow(
                        row.getLong("row_number"),
                        readProperties(row.getString("payload"))))
                .list();
    }

    private long deleteMissing(ImportWork work) {
        var schema = repository.schema(work.ontologyId(), work.objectTypeApiName());
        long deleted = 0;
        String cursor = null;
        do {
            var page = repository.list(schema, 200, cursor);
            for (var instance : page.items()) {
                if ("DATASET".equals(instance.sourceKind())
                        && work.datasetId().toString().equals(instance.sourceRef())
                        && !stagedContains(work.id(), instance.id())) {
                    instances.deleteFromSource(
                            work.ontologyId(),
                            work.ontologyApiName(),
                            work.objectTypeApiName(),
                            instance.id(),
                            instance.version(),
                            "DATASET",
                            work.id());
                    deleted++;
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null);
        return deleted;
    }

    private boolean stagedContains(UUID jobId, String objectId) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM control.dataset_object_import_staging
                  WHERE job_id=:job AND object_id=:objectId)
                """).param("job", jobId)
                .param("objectId", objectId)
                .query(Boolean.class)
                .single();
    }

    private void progress(
            UUID jobId,
            AtomicLong inserted,
            AtomicLong updated,
            AtomicLong unchanged,
            AtomicLong failed) {
        jdbc.sql("""
                UPDATE control.dataset_object_import_jobs
                SET inserted_count=:inserted,updated_count=:updated,
                    unchanged_count=:unchanged,failed_count=:failed
                WHERE id=:id
                """).param("inserted", inserted.get())
                .param("updated", updated.get())
                .param("unchanged", unchanged.get())
                .param("failed", failed.get())
                .param("id", jobId)
                .update();
    }

    private void error(UUID jobId, long rowNumber, RuntimeException failure) {
        String code = failure instanceof ObjectInstanceStoreException problem
                ? problem.code()
                : "IMPORT_ROW_INVALID";
        jdbc.sql("""
                INSERT INTO control.dataset_object_import_errors(
                  job_id,row_number,error_code,safe_message)
                VALUES (:job,:rowNumber,:code,:message)
                ON CONFLICT DO NOTHING
                """).param("job", jobId)
                .param("rowNumber", rowNumber)
                .param("code", code)
                .param("message", safeError(failure))
                .update();
    }

    private boolean cancelRequested(UUID jobId) {
        return jdbc.sql("""
                SELECT cancel_requested FROM control.dataset_object_import_jobs WHERE id=:id
                """).param("id", jobId).query(Boolean.class).single();
    }

    private ObjectInstanceImportJob getInternal(
            UUID id, UUID ontologyId, UUID objectTypeId) {
        return jdbc.sql("""
                SELECT * FROM control.dataset_object_import_jobs
                WHERE id=:id AND ontology_id=:ontology AND object_type_id=:objectType
                """).param("id", id)
                .param("ontology", ontologyId)
                .param("objectType", objectTypeId)
                .query((row, number) -> new ObjectInstanceImportJob()
                        .id(row.getObject("id", UUID.class))
                        .status(ObjectInstanceImportJob.StatusEnum.fromValue(
                                row.getString("status")))
                        .inserted(row.getLong("inserted_count"))
                        .updated(row.getLong("updated_count"))
                        .deleted(row.getLong("deleted_count"))
                        .unchanged(row.getLong("unchanged_count"))
                        .failed(row.getLong("failed_count"))
                        .safeError(row.getString("safe_error"))
                        .createdAt(row.getTimestamp("created_at")
                                .toInstant()
                                .atOffset(ZoneOffset.UTC))
                        .completedAt(row.getTimestamp("completed_at") == null
                                ? null
                                : row.getTimestamp("completed_at")
                                        .toInstant()
                                        .atOffset(ZoneOffset.UTC)))
                .optional()
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "IMPORT_NOT_FOUND", "Import job does not exist"));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Import mapping cannot be encoded", failure);
        }
    }

    private Map<String, String> readMapping(String value) {
        try {
            return json.readValue(value, MAPPING_TYPE);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored import mapping is invalid", failure);
        }
    }

    private Map<String, Object> readProperties(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception failure) {
            throw new IllegalStateException("Staged import payload is invalid", failure);
        }
    }

    private String safeError(Throwable failure) {
        String value = failure instanceof ObjectInstanceStoreException
                ? failure.getMessage()
                : "Dataset row could not be imported";
        return value.substring(0, Math.min(1000, value.length()));
    }

    private record ImportWork(
            UUID id,
            UUID ontologyId,
            UUID datasetId,
            UUID objectTypeId,
            String mode,
            String identityField,
            String titleField,
            Map<String, String> fieldMappings,
            String ontologyApiName,
            String objectTypeApiName) {}

    private record StagedRow(long rowNumber, Map<String, Object> properties) {}

    private static final class ImportCancelled extends RuntimeException {}
}
