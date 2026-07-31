package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.model.BulkObjectInstanceItemResult.StatusEnum.CREATED;
import static com.hezhangjian.ontology.model.BulkObjectInstanceItemResult.StatusEnum.DELETED;
import static com.hezhangjian.ontology.model.BulkObjectInstanceItemResult.StatusEnum.FAILED;
import static com.hezhangjian.ontology.model.BulkObjectInstanceItemResult.StatusEnum.UPDATED;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.model.BulkObjectInstanceItem;
import com.hezhangjian.ontology.model.BulkObjectInstanceItemResult;
import com.hezhangjian.ontology.model.BulkObjectInstanceReq;
import com.hezhangjian.ontology.model.BulkObjectInstanceResult;
import com.hezhangjian.ontology.model.ObjectInstance;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ObjectInstanceBulkService {
    private final ObjectInstanceApiService instances;
    private final ObjectMapper json;
    private final OntologyLookupService ontologies;
    private final ObjectInstanceRepository repository;
    private final TransactionTemplate transactions;

    public ObjectInstanceBulkService(
            ObjectInstanceApiService instances,
            ObjectMapper json,
            OntologyLookupService ontologies,
            ObjectInstanceRepository repository,
            PlatformTransactionManager transactionManager) {
        this.instances = instances;
        this.json = json;
        this.ontologies = ontologies;
        this.repository = repository;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public BulkObjectInstanceResult mutate(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            BulkObjectInstanceReq request) {
        return transactions.execute(status ->
                idempotentMutation(
                        idempotencyKey, objectTypeId, ontologyId, request));
    }

    private BulkObjectInstanceResult idempotentMutation(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            BulkObjectInstanceReq request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ObjectInstanceStoreException(
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "Idempotency-Key is required for bulk mutations");
        }
        UUID ontologyUuid = ontologies.resolve(ontologyId);
        UUID objectTypeUuid =
                repository.schema(ontologyUuid, objectTypeId).objectTypeId();
        String requestHash = fingerprint(request);
        repository.acquireBulkIdempotencyLock(
                ontologyUuid, objectTypeUuid, idempotencyKey);
        var replay = repository.bulkIdempotency(
                ontologyUuid, objectTypeUuid, idempotencyKey);
        if (replay.isPresent()) {
            if (!replay.get().requestHash().equals(requestHash)) {
                throw new ObjectInstanceStoreException(
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was already used with a different request");
            }
            return readResult(replay.get().response());
        }
        BulkObjectInstanceResult result =
                mutateItems(idempotencyKey, objectTypeId, ontologyId, request);
        repository.saveBulkIdempotency(
                ontologyUuid,
                objectTypeUuid,
                idempotencyKey,
                requestHash,
                writeResult(result));
        return result;
    }

    private BulkObjectInstanceResult mutateItems(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            BulkObjectInstanceReq request) {
        List<BulkObjectInstanceItemResult> results = new ArrayList<>();
        for (int index = 0; index < request.getItems().size(); index++) {
            BulkObjectInstanceItem item = request.getItems().get(index);
            try {
                ObjectInstance value = mutateItem(
                        idempotencyKey + ":" + index,
                        objectTypeId,
                        ontologyId,
                        request.getOperation(),
                        item);
                results.add(success(index, request.getOperation(), item, value));
            } catch (ObjectInstanceStoreException failure) {
                results.add(new BulkObjectInstanceItemResult()
                        .index(index)
                        .id(item.getId())
                        .status(FAILED)
                        .errorCode(failure.code())
                        .safeError(failure.getMessage()));
                if (Boolean.TRUE.equals(request.getAtomic())) {
                    throw failure;
                }
            }
        }
        return new BulkObjectInstanceResult().items(results);
    }

    private BulkObjectInstanceItemResult success(
            int index,
            BulkObjectInstanceReq.OperationEnum operation,
            BulkObjectInstanceItem item,
            ObjectInstance value) {
        var status = operation == BulkObjectInstanceReq.OperationEnum.CREATE
                        || (operation == BulkObjectInstanceReq.OperationEnum.UPSERT
                                && value != null
                                && value.getVersion() == 1)
                ? CREATED
                : operation == BulkObjectInstanceReq.OperationEnum.DELETE
                        ? DELETED
                        : UPDATED;
        return new BulkObjectInstanceItemResult()
                .index(index)
                .id(value == null ? item.getId() : value.getId())
                .status(status)
                .version(value == null ? item.getVersion() : value.getVersion());
    }

    private ObjectInstance mutateItem(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            BulkObjectInstanceReq.OperationEnum operation,
            BulkObjectInstanceItem item) {
        return switch (operation) {
            case CREATE -> instances.create(
                    ontologyId, objectTypeId, item.getProperties(), idempotencyKey);
            case DELETE -> {
                requireIdAndVersion(item);
                instances.delete(
                        ontologyId,
                        objectTypeId,
                        item.getId(),
                        Long.toString(item.getVersion()));
                yield null;
            }
            case UPDATE -> {
                requireIdAndVersion(item);
                yield instances.update(
                        ontologyId,
                        objectTypeId,
                        item.getId(),
                        Long.toString(item.getVersion()),
                        item.getProperties());
            }
            case UPSERT -> upsert(
                    idempotencyKey, objectTypeId, ontologyId, item);
        };
    }

    private ObjectInstance upsert(
            String idempotencyKey,
            String objectTypeId,
            String ontologyId,
            BulkObjectInstanceItem item) {
        if (item.getId() != null && item.getVersion() != null) {
            return instances.update(
                    ontologyId,
                    objectTypeId,
                    item.getId(),
                    Long.toString(item.getVersion()),
                    item.getProperties());
        }
        if (item.getId() != null) {
            try {
                ObjectInstance current =
                        instances.get(ontologyId, objectTypeId, item.getId());
                return instances.update(
                        ontologyId,
                        objectTypeId,
                        item.getId(),
                        Long.toString(current.getVersion()),
                        item.getProperties());
            } catch (ObjectInstanceStoreException failure) {
                if (!"OBJECT_INSTANCE_NOT_FOUND".equals(failure.code())) {
                    throw failure;
                }
            }
        }
        return instances.create(
                ontologyId, objectTypeId, item.getProperties(), idempotencyKey);
    }

    private void requireIdAndVersion(BulkObjectInstanceItem item) {
        if (item.getId() == null || item.getVersion() == null) {
            throw new ObjectInstanceStoreException(
                    "BULK_ITEM_INVALID", "Update and delete items require id and version");
        }
    }

    private String fingerprint(BulkObjectInstanceReq request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(request)
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot fingerprint bulk request", failure);
        }
    }

    private BulkObjectInstanceResult readResult(String value) {
        try {
            return json.readValue(value, BulkObjectInstanceResult.class);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Stored bulk idempotency response is invalid", failure);
        }
    }

    private String writeResult(BulkObjectInstanceResult value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "Cannot store bulk idempotency response", failure);
        }
    }
}
