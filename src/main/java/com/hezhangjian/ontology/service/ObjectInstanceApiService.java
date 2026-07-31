package com.hezhangjian.ontology.service;

import static com.hezhangjian.ontology.instance.ObjectInstanceModels.MutationResult;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.repo.ObjectInstanceRepository;
import com.hezhangjian.ontology.instance.ObjectInstanceService;
import com.hezhangjian.ontology.model.ObjectInstance;
import com.hezhangjian.ontology.model.ObjectInstancePage;
import com.hezhangjian.ontology.model.ObjectInstanceQueryReq;
import com.hezhangjian.ontology.model.ObjectInstanceAggregateReq;
import com.hezhangjian.ontology.model.ObjectInstanceAggregateResult;
import com.hezhangjian.ontology.model.ObjectInstanceProjectionStatus;
import com.hezhangjian.ontology.model.ObjectInstanceProjectionTargetStatus;
import com.hezhangjian.ontology.security.WorkspaceContext;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class ObjectInstanceApiService {
    private static final TypeReference<Map<String, Object>> PROPERTIES = new TypeReference<>() {};

    private final OntologyLookupService catalogs;
    private final ObjectInstanceRepository repository;
    private final ObjectInstanceService instances;
    private final ObjectMapper json;

    public ObjectInstanceApiService(
            OntologyLookupService catalogs,
            ObjectInstanceRepository repository,
            ObjectInstanceService instances,
            ObjectMapper json) {
        this.catalogs = catalogs;
        this.repository = repository;
        this.instances = instances;
        this.json = json;
    }

    public ObjectInstance create(
            String ontologyApiName,
            String objectTypeApiName,
            Object properties,
            String idempotencyKey) {
        return inOntology(ontologyApiName, ontologyId -> {
            MutationResult result = instances.create(
                    ontologyId,
                    ontologyApiName,
                    objectTypeApiName,
                    propertyMap(properties),
                    idempotencyKey);
            return model(
                    repository.schema(ontologyId, objectTypeApiName),
                    result.instance(),
                    result.correlationId());
        });
    }

    public ObjectInstance get(
            String ontologyApiName, String objectTypeApiName, String objectId) {
        return inOntology(ontologyApiName, ontologyId -> model(
                repository.schema(ontologyId, objectTypeApiName),
                instances.get(ontologyId, objectTypeApiName, objectId),
                null));
    }

    public ObjectInstancePage list(
            String ontologyApiName,
            String objectTypeApiName,
            Integer pageSize,
            String cursor) {
        return inOntology(ontologyApiName, ontologyId -> {
            ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
            var page = instances.list(ontologyId, objectTypeApiName, pageSize, cursor);
            return new ObjectInstancePage()
                    .type(objectTypeApiName)
                    .items(page.items().stream()
                            .map(item -> model(schema, item, null))
                            .toList())
                    .total((long) page.items().size())
                    .totalIsLowerBound(page.nextCursor() != null)
                    .nextCursor(instances.encodeCursor(page.nextCursor()));
        });
    }

    public ObjectInstancePage query(
            String ontologyApiName,
            String objectTypeApiName,
            ObjectInstanceQueryReq request) {
        return inOntology(ontologyApiName, ontologyId -> {
            ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
            var filters = request.getFilters() == null
                    ? List.<com.hezhangjian.ontology.instance.ObjectInstanceModels.QueryFilter>of()
                    : request.getFilters().stream()
                            .map(filter -> new com.hezhangjian.ontology.instance.ObjectInstanceModels.QueryFilter(
                                    filter.getProperty(),
                                    filter.getOperator().getValue(),
                                    filter.getValue()))
                            .toList();
            var sorts = request.getSort() == null
                    ? List.<com.hezhangjian.ontology.instance.ObjectInstanceModels.QuerySort>of()
                    : request.getSort().stream()
                            .map(sort -> new com.hezhangjian.ontology.instance.ObjectInstanceModels.QuerySort(
                                    sort.getProperty(), sort.getDirection().getValue()))
                            .toList();
            var page = repository.query(
                    schema,
                    filters,
                    request.getFilterOperator() == null
                            ? "AND"
                            : request.getFilterOperator().getValue(),
                    sorts,
                    request.getPageSize() == null ? 50 : request.getPageSize().getValue(),
                    instances.decodeCursor(request.getCursor()));
            java.util.Set<String> selected = request.getProperties() == null
                    ? java.util.Set.of()
                    : request.getProperties().stream()
                            .map(name -> externalName(schema, name))
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new ObjectInstancePage()
                    .type(objectTypeApiName)
                    .items(page.items().stream()
                            .map(item -> model(schema, item, null, selected))
                            .toList())
                    .total((long) page.items().size())
                    .totalIsLowerBound(page.nextCursor() != null)
                    .nextCursor(instances.encodeCursor(page.nextCursor()));
        });
    }

    public ObjectInstanceAggregateResult aggregate(
            String ontologyApiName,
            String objectTypeApiName,
            ObjectInstanceAggregateReq request) {
        return inOntology(ontologyApiName, ontologyId -> {
            ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
            var filters = request.getFilters() == null
                    ? List.<com.hezhangjian.ontology.instance.ObjectInstanceModels.QueryFilter>of()
                    : request.getFilters().stream()
                            .map(filter -> new com.hezhangjian.ontology.instance.ObjectInstanceModels.QueryFilter(
                                    filter.getProperty(),
                                    filter.getOperator().getValue(),
                                    filter.getValue()))
                            .toList();
            var metrics = request.getMetrics().stream()
                    .map(metric -> new com.hezhangjian.ontology.instance.ObjectInstanceModels.AggregateMetric(
                            metric.getOperation().getValue(),
                            metric.getProperty(),
                            metric.getAlias()))
                    .toList();
            List<Map<String, Object>> rows = repository.aggregate(
                    schema,
                    filters,
                    request.getFilterOperator() == null
                            ? "AND"
                            : request.getFilterOperator().getValue(),
                    request.getGroupBy() == null
                            ? List.of()
                            : new java.util.ArrayList<>(request.getGroupBy()),
                    metrics);
            return new ObjectInstanceAggregateResult().rows(
                    rows.stream().map(value -> (Object) value).toList());
        });
    }

    public ObjectInstance update(
            String ontologyApiName,
            String objectTypeApiName,
            String objectId,
            String ifMatch,
            Object properties) {
        return inOntology(ontologyApiName, ontologyId -> {
            MutationResult result = instances.update(
                    ontologyId,
                    ontologyApiName,
                    objectTypeApiName,
                    objectId,
                    version(ifMatch),
                    propertyMap(properties));
            return model(
                    repository.schema(ontologyId, objectTypeApiName),
                    result.instance(),
                    result.correlationId());
        });
    }

    public void delete(
            String ontologyApiName,
            String objectTypeApiName,
            String objectId,
            String ifMatch) {
        inOntology(ontologyApiName, ontologyId -> {
            instances.delete(
                    ontologyId,
                    ontologyApiName,
                    objectTypeApiName,
                    objectId,
                    version(ifMatch));
            return null;
        });
    }

    public ObjectInstanceProjectionStatus projectionStatus(
            String ontologyApiName, String objectTypeApiName, String objectId) {
        return inOntology(ontologyApiName, ontologyId -> {
            ObjectSchema schema = repository.schema(ontologyId, objectTypeApiName);
            StoredInstance current = instances.get(ontologyId, objectTypeApiName, objectId);
            List<ObjectInstanceProjectionTargetStatus> targets = repository
                    .projectionStatus(ontologyId, schema.objectTypeId(), objectId)
                    .stream()
                    .map(status -> new ObjectInstanceProjectionTargetStatus()
                            .target(ObjectInstanceProjectionTargetStatus.TargetEnum.fromValue(
                                    status.target()))
                            .version(status.projectedVersion())
                            .status(status.status())
                            .lastError(status.lastError())
                            .updatedAt(status.updatedAt().atOffset(ZoneOffset.UTC)))
                    .toList();
            return new ObjectInstanceProjectionStatus()
                    .objectId(objectId)
                    .authoritativeVersion(current.version())
                    .targets(targets);
        });
    }

    public long parseVersion(String ifMatch) {
        return version(ifMatch);
    }

    public Map<String, Object> propertyMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return json.convertValue(value, PROPERTIES);
    }

    private ObjectInstance model(
            ObjectSchema schema, StoredInstance source, UUID correlationId) {
        return model(schema, source, correlationId, java.util.Set.of());
    }

    private ObjectInstance model(
            ObjectSchema schema,
            StoredInstance source,
            UUID correlationId,
            java.util.Set<String> selected) {
        Map<String, Object> properties = instances.externalProperties(schema, source);
        if (!selected.isEmpty()) {
            properties = properties.entrySet().stream()
                    .filter(entry -> selected.contains(entry.getKey()))
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (left, right) -> left,
                            java.util.LinkedHashMap::new));
        }
        return new ObjectInstance()
                .id(source.id())
                .type(schema.objectTypeApiName())
                .title(source.title())
                .properties(properties)
                .version(source.version())
                .correlationId(correlationId)
                .createdAt(source.createdAt().atOffset(ZoneOffset.UTC))
                .updatedAt(source.updatedAt().atOffset(ZoneOffset.UTC));
    }

    private long version(String value) {
        try {
            String normalized = value == null
                    ? ""
                    : value.replace("W/", "").replace("\"", "").trim();
            long version = Long.parseLong(normalized);
            if (version < 1) {
                throw new NumberFormatException("version must be positive");
            }
            return version;
        } catch (RuntimeException failure) {
            throw new com.hezhangjian.ontology.instance.ObjectInstanceStoreException(
                    "ETAG_INVALID", "If-Match must contain a positive instance version", failure);
        }
    }

    private String externalName(ObjectSchema schema, String name) {
        var property = schema.property(name);
        if (property == null) {
            throw new com.hezhangjian.ontology.instance.ObjectInstanceStoreException(
                    "PROPERTY_UNKNOWN", "Unknown property: " + name);
        }
        return property.displayName();
    }

    private <T> T inOntology(String apiName, Function<UUID, T> work) {
        UUID ontologyId = catalogs.resolve(apiName);
        catalogs.get(ontologyId);
        return WorkspaceContext.call(ontologyId, () -> work.apply(ontologyId));
    }
}
