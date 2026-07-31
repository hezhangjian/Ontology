package com.hezhangjian.ontology.repo;

import static com.hezhangjian.ontology.instance.ObjectInstanceModels.InstancePage;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.PropertySchema;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.ProjectionStatus;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.QueryFilter;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.QuerySort;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.AggregateMetric;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.StoredInstance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.instance.ObjectInstanceEvent;
import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.instance.SqlIdentifierResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ObjectInstanceRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ObjectMapper json;
    private final SqlIdentifierResolver identifiers;
    private final ObjectInstanceSchemaRepository schemas;

    public ObjectInstanceRepository(
            DataSource dataSource,
            ObjectMapper json,
            SqlIdentifierResolver identifiers,
            ObjectInstanceSchemaRepository schemas) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.named = new NamedParameterJdbcTemplate(dataSource);
        this.json = json;
        this.identifiers = identifiers;
        this.schemas = schemas;
    }

    public ObjectSchema schema(UUID ontologyId, String objectTypeApiName) {
        return schemas.resolve(ontologyId, objectTypeApiName);
    }

    public void ensureTable(ObjectSchema schema) {
        schemas.ensureTable(schema);
    }

    public StoredInstance insert(
            ObjectSchema schema,
            String objectId,
            String title,
            JsonNode basePayload,
            JsonNode overridePayload,
            String sourceKind,
            String sourceRef,
            String sourceRevision) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        DynamicColumns columns = columns(schema, basePayload, overridePayload);
        MapSqlParameterSource parameters = commonParameters(
                objectId, title, basePayload, overridePayload, sourceKind, sourceRef, sourceRevision);
        parameters.addValues(columns.parameters());
        String sql = """
                INSERT INTO %s (
                  _object_id,_title,_version,_base_payload,_override_payload,
                  _source_kind,_source_ref,_source_revision%s)
                VALUES (
                  :objectId,:title,1,CAST(:basePayload AS jsonb),CAST(:overridePayload AS jsonb),
                  :sourceKind,:sourceRef,:sourceRevision%s)
                """.formatted(table, columns.names(), columns.values());
        try {
            named.update(sql, parameters);
        } catch (DuplicateKeyException failure) {
            throw new ObjectInstanceStoreException(
                    "OBJECT_INSTANCE_EXISTS", "Object instance already exists", failure);
        }
        return find(schema, objectId, true).orElseThrow();
    }

    public boolean backfill(
            ObjectSchema schema,
            String objectId,
            String title,
            long version,
            JsonNode basePayload,
            JsonNode overridePayload,
            boolean deleted) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        DynamicColumns columns = columns(schema, basePayload, overridePayload);
        MapSqlParameterSource parameters = commonParameters(
                objectId, title, basePayload, overridePayload, "MIGRATION", null, null);
        parameters.addValue("version", Math.max(1, version))
                .addValue("deleted", deleted)
                .addValues(columns.parameters());
        int changed = named.update("""
                INSERT INTO %s (
                  _object_id,_title,_version,_base_payload,_override_payload,
                  _source_kind,_source_ref,_source_revision,_deleted_at%s)
                VALUES (
                  :objectId,:title,:version,CAST(:basePayload AS jsonb),
                  CAST(:overridePayload AS jsonb),:sourceKind,:sourceRef,:sourceRevision,
                  CASE WHEN :deleted THEN now() ELSE NULL END%s)
                ON CONFLICT(_object_id) DO NOTHING
                """.formatted(table, columns.names(), columns.values()), parameters);
        return changed > 0;
    }

    public Optional<StoredInstance> find(ObjectSchema schema, String objectId, boolean includeDeleted) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        String sql = "SELECT * FROM " + table + " WHERE _object_id=?"
                + (includeDeleted ? "" : " AND _deleted_at IS NULL");
        return jdbc.query(sql, this::instance, objectId).stream().findFirst();
    }

    public long count(ObjectSchema schema) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE _deleted_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    public StoredInstance update(
            ObjectSchema schema,
            String objectId,
            long expectedVersion,
            String title,
            JsonNode basePayload,
            JsonNode overridePayload,
            String sourceKind,
            String sourceRef,
            String sourceRevision) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        DynamicColumns columns = columns(schema, basePayload, overridePayload);
        MapSqlParameterSource parameters = commonParameters(
                objectId, title, basePayload, overridePayload, sourceKind, sourceRef, sourceRevision);
        parameters.addValue("expectedVersion", expectedVersion).addValues(columns.parameters());
        int updated = named.update("""
                UPDATE %s SET
                  _title=:title,_version=_version+1,
                  _base_payload=CAST(:basePayload AS jsonb),
                  _override_payload=CAST(:overridePayload AS jsonb),
                  _source_kind=:sourceKind,_source_ref=:sourceRef,
                  _source_revision=:sourceRevision,_updated_at=now()%s
                WHERE _object_id=:objectId AND _version=:expectedVersion
                  AND _deleted_at IS NULL
                """.formatted(table, columns.assignments()), parameters);
        if (updated == 0) {
            requireVersion(schema, objectId, expectedVersion);
        }
        return find(schema, objectId, false).orElseThrow();
    }

    public StoredInstance revive(
            ObjectSchema schema,
            String objectId,
            long expectedVersion,
            String title,
            JsonNode basePayload,
            JsonNode overridePayload,
            String sourceKind,
            String sourceRef,
            String sourceRevision) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        DynamicColumns columns = columns(schema, basePayload, overridePayload);
        MapSqlParameterSource parameters = commonParameters(
                objectId, title, basePayload, overridePayload, sourceKind, sourceRef, sourceRevision);
        parameters.addValue("expectedVersion", expectedVersion).addValues(columns.parameters());
        int updated = named.update("""
                UPDATE %s SET
                  _title=:title,_version=_version+1,
                  _base_payload=CAST(:basePayload AS jsonb),
                  _override_payload=CAST(:overridePayload AS jsonb),
                  _source_kind=:sourceKind,_source_ref=:sourceRef,
                  _source_revision=:sourceRevision,_deleted_at=NULL,_updated_at=now()%s
                WHERE _object_id=:objectId AND _version=:expectedVersion
                  AND _deleted_at IS NOT NULL
                """.formatted(table, columns.assignments()), parameters);
        if (updated == 0) {
            throw new ObjectInstanceStoreException(
                    "VERSION_CONFLICT", "Deleted object instance changed before it could be restored");
        }
        return find(schema, objectId, false).orElseThrow();
    }

    public StoredInstance tombstone(ObjectSchema schema, String objectId, long expectedVersion) {
        ensureTable(schema);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        int updated = jdbc.update(
                "UPDATE " + table
                        + " SET _version=_version+1,_deleted_at=now(),_updated_at=now()"
                        + " WHERE _object_id=? AND _version=? AND _deleted_at IS NULL",
                objectId,
                expectedVersion);
        if (updated == 0) {
            requireVersion(schema, objectId, expectedVersion);
        }
        return find(schema, objectId, true).orElseThrow();
    }

    public InstancePage list(ObjectSchema schema, int pageSize, String cursor) {
        ensureTable(schema);
        int limit = Math.min(Math.max(pageSize, 1), 200);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        String sql = "SELECT * FROM " + table + " WHERE _deleted_at IS NULL"
                + (cursor == null ? "" : " AND _object_id>?")
                + " ORDER BY _object_id LIMIT ?";
        List<StoredInstance> values = cursor == null
                ? jdbc.query(sql, this::instance, limit + 1)
                : jdbc.query(sql, this::instance, cursor, limit + 1);
        String next = values.size() > limit ? values.get(limit - 1).id() : null;
        return new InstancePage(
                values.size() > limit ? List.copyOf(values.subList(0, limit)) : values, next);
    }

    public List<StoredInstance> all(ObjectSchema schema, int limit, String cursor) {
        return list(schema, limit, cursor).items();
    }

    public InstancePage query(
            ObjectSchema schema,
            List<QueryFilter> filters,
            String filterOperator,
            List<QuerySort> sorts,
            int pageSize,
            String cursor) {
        ensureTable(schema);
        int limit = Math.min(Math.max(pageSize, 1), 200);
        MapSqlParameterSource parameters =
                new MapSqlParameterSource().addValue("limit", limit + 1);
        if (cursor != null) {
            parameters.addValue("cursor", cursor);
        }
        String where = filterSql(schema, filters, filterOperator, parameters);
        String order = orderSql(schema, sorts);
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        String sql = "SELECT current_row.* FROM "
                + table
                + " current_row"
                + " WHERE _deleted_at IS NULL"
                + (cursor == null ? "" : " AND (" + cursorSql(schema, sorts, table) + ")")
                + where
                + " ORDER BY "
                + order
                + " LIMIT :limit";
        List<StoredInstance> values = named.query(sql, parameters, this::instance);
        String next = values.size() > limit ? values.get(limit - 1).id() : null;
        return new InstancePage(
                values.size() > limit ? List.copyOf(values.subList(0, limit)) : values, next);
    }

    public List<Map<String, Object>> aggregate(
            ObjectSchema schema,
            List<QueryFilter> filters,
            String filterOperator,
            List<String> groupBy,
            List<AggregateMetric> metrics) {
        ensureTable(schema);
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        String where = filterSql(schema, filters, filterOperator, parameters);
        List<String> select = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        for (int index = 0; index < groupBy.size(); index++) {
            PropertySchema property = property(schema, groupBy.get(index));
            String column = identifiers.quote(property.physicalKey());
            String alias = "g" + index;
            select.add(column + " AS " + alias);
            groups.add(column);
            aliases.put(alias, property.displayName());
        }
        for (int index = 0; index < metrics.size(); index++) {
            AggregateMetric metric = metrics.get(index);
            String alias = "m" + index;
            String expression = aggregateExpression(schema, metric);
            select.add(expression + " AS " + alias);
            aliases.put(alias, metric.alias() == null || metric.alias().isBlank()
                    ? metric.operation().toLowerCase(java.util.Locale.ROOT) + index
                    : metric.alias());
        }
        String sql = "SELECT " + String.join(",", select)
                + " FROM " + identifiers.qualified(schema.schemaName(), schema.tableName())
                + " WHERE _deleted_at IS NULL" + where
                + (groups.isEmpty() ? "" : " GROUP BY " + String.join(",", groups))
                + " LIMIT 1000";
        return named.query(sql, parameters, (row, number) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            aliases.forEach((alias, external) -> {
                try {
                    result.put(external, row.getObject(alias));
                } catch (SQLException failure) {
                    throw new IllegalStateException("Aggregate result is invalid", failure);
                }
            });
            return result;
        });
    }

    public void enqueue(
            ObjectSchema schema, ObjectInstanceEvent event, String ontologyApiName, String payload) {
        jdbc.update(
                """
                INSERT INTO control.object_instance_outbox(
                  id,event_id,ontology_id,object_type_id,object_id,version,
                  correlation_id,topic,message_key,payload)
                VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """,
                UUID.randomUUID(),
                event.eventId(),
                schema.ontologyId(),
                schema.objectTypeId(),
                event.objectId(),
                event.version(),
                event.correlationId(),
                "persistent://ontology/object-instance/" + schema.objectTypeApiName(),
                ontologyApiName + ":" + schema.objectTypeApiName() + ":" + event.objectId(),
                payload);
    }

    public Optional<IdempotencyRecord> idempotency(
            UUID ontologyId, UUID objectTypeId, String key) {
        return jdbc.query(
                        """
                        SELECT request_hash,object_id,response_version
                        FROM control.object_instance_idempotency
                        WHERE ontology_id=? AND object_type_id=? AND idempotency_key=?
                          AND expires_at>now()
                        """,
                        (row, number) -> new IdempotencyRecord(
                                row.getString("request_hash"),
                                row.getString("object_id"),
                                row.getLong("response_version")),
                        ontologyId,
                        objectTypeId,
                        key)
                .stream()
                .findFirst();
    }

    public void saveIdempotency(
            UUID ontologyId,
            UUID objectTypeId,
            String key,
            String hash,
            String objectId,
            long version) {
        jdbc.update(
                """
                INSERT INTO control.object_instance_idempotency(
                  ontology_id,object_type_id,idempotency_key,request_hash,object_id,response_version)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(ontology_id,object_type_id,idempotency_key) DO NOTHING
                """,
                ontologyId,
                objectTypeId,
                key,
                hash,
                objectId,
                version);
    }

    public void acquireBulkIdempotencyLock(
            UUID ontologyId, UUID objectTypeId, String idempotencyKey) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                row -> {},
                ontologyId + ":" + objectTypeId + ":" + idempotencyKey);
    }

    public Optional<BulkIdempotencyRecord> bulkIdempotency(
            UUID ontologyId, UUID objectTypeId, String idempotencyKey) {
        return jdbc.query(
                        """
                        SELECT request_hash,response::text
                        FROM control.object_instance_bulk_idempotency
                        WHERE ontology_id=? AND object_type_id=? AND idempotency_key=?
                          AND expires_at>now()
                        """,
                        (row, number) -> new BulkIdempotencyRecord(
                                row.getString("request_hash"), row.getString("response")),
                        ontologyId,
                        objectTypeId,
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    public void saveBulkIdempotency(
            UUID ontologyId,
            UUID objectTypeId,
            String idempotencyKey,
            String requestHash,
            String response) {
        jdbc.update(
                """
                INSERT INTO control.object_instance_bulk_idempotency(
                  ontology_id,object_type_id,idempotency_key,request_hash,response)
                VALUES (?,?,?,?,?::jsonb)
                """,
                ontologyId,
                objectTypeId,
                idempotencyKey,
                requestHash,
                response);
    }

    public List<ProjectionStatus> projectionStatus(
            UUID ontologyId, UUID objectTypeId, String objectId) {
        return jdbc.query(
                """
                SELECT target,projected_version,status,last_error,updated_at
                FROM control.object_instance_projection_state
                WHERE ontology_id=? AND object_type_id=? AND object_id=?
                ORDER BY target
                """,
                (row, number) -> new ProjectionStatus(
                        row.getString("target"),
                        row.getLong("projected_version"),
                        row.getString("status"),
                        row.getString("last_error"),
                        row.getTimestamp("updated_at").toInstant()),
                ontologyId,
                objectTypeId,
                objectId);
    }

    private void requireVersion(ObjectSchema schema, String objectId, long expectedVersion) {
        StoredInstance current = find(schema, objectId, true)
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "OBJECT_INSTANCE_NOT_FOUND", "Object instance does not exist"));
        if (current.deletedAt() != null) {
            throw new ObjectInstanceStoreException(
                    "OBJECT_INSTANCE_NOT_FOUND", "Object instance does not exist");
        }
        throw new ObjectInstanceStoreException(
                "VERSION_CONFLICT",
                "Expected version " + expectedVersion + " but current version is " + current.version());
    }

    private String filterSql(
            ObjectSchema schema,
            List<QueryFilter> filters,
            String filterOperator,
            MapSqlParameterSource parameters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index < filters.size(); index++) {
            QueryFilter filter = filters.get(index);
            PropertySchema property = property(schema, filter.propertyId());
            String column = identifiers.quote(property.physicalKey());
            String parameter = "filter" + index;
            String operator = filter.operator().toLowerCase(java.util.Locale.ROOT);
            switch (operator) {
                case "is_empty" -> clauses.add("(" + column + " IS NULL OR CAST("
                        + column + " AS text)='')");
                case "is_not_empty" -> clauses.add("(" + column + " IS NOT NULL AND CAST("
                        + column + " AS text)<>'')");
                case "starts_with" -> {
                    clauses.add(column + " LIKE :" + parameter);
                    parameters.addValue(parameter, String.valueOf(filter.value()) + "%");
                }
                case "in", "not_in" -> {
                    if (!(filter.value() instanceof List<?> values) || values.isEmpty()) {
                        throw new ObjectInstanceStoreException(
                                "FILTER_VALUE_INVALID", operator + " requires a non-empty array");
                    }
                    clauses.add(column + ("in".equals(operator) ? " IN (:" : " NOT IN (:")
                            + parameter + ")");
                    parameters.addValue(parameter, values);
                }
                case "between" -> {
                    if (!(filter.value() instanceof List<?> values) || values.size() != 2) {
                        throw new ObjectInstanceStoreException(
                                "FILTER_VALUE_INVALID", "between requires two values");
                    }
                    clauses.add(column + " BETWEEN :" + parameter + "a AND :" + parameter + "b");
                    parameters.addValue(parameter + "a", values.get(0));
                    parameters.addValue(parameter + "b", values.get(1));
                }
                default -> {
                    String sqlOperator = switch (operator) {
                        case "eq", "exact" -> "=";
                        case "ne" -> "<>";
                        case "gt" -> ">";
                        case "gte" -> ">=";
                        case "lt" -> "<";
                        case "lte" -> "<=";
                        default -> throw new ObjectInstanceStoreException(
                                "FILTER_OPERATOR_INVALID", "Unsupported filter operator");
                    };
                    clauses.add(column + sqlOperator + ":" + parameter);
                    parameters.addValue(parameter, filter.value());
                }
            }
        }
        String conjunction = "OR".equalsIgnoreCase(filterOperator) ? " OR " : " AND ";
        return " AND (" + String.join(conjunction, clauses) + ")";
    }

    private String orderSql(ObjectSchema schema, List<QuerySort> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            return "_object_id";
        }
        List<String> clauses = new ArrayList<>();
        for (QuerySort sort : sorts) {
            PropertySchema property = property(schema, sort.propertyId());
            String direction = "desc".equalsIgnoreCase(sort.direction()) ? "DESC" : "ASC";
            clauses.add(identifiers.quote(property.physicalKey()) + " " + direction
                    + " NULLS LAST");
        }
        clauses.add("_object_id");
        return String.join(",", clauses);
    }

    private String cursorSql(
            ObjectSchema schema, List<QuerySort> sorts, String table) {
        if (sorts == null || sorts.isEmpty()) {
            return "_object_id>:cursor";
        }
        List<String> terms = new ArrayList<>();
        List<String> equalPrefix = new ArrayList<>();
        for (QuerySort sort : sorts) {
            PropertySchema property = property(schema, sort.propertyId());
            String column = identifiers.quote(property.physicalKey());
            String current = "current_row." + column;
            String reference = "(SELECT cursor_row." + column + " FROM " + table
                    + " cursor_row WHERE cursor_row._object_id=:cursor)";
            String comparison =
                    "desc".equalsIgnoreCase(sort.direction()) ? "<" : ">";
            List<String> conditions = new ArrayList<>(equalPrefix);
            conditions.add(reference + " IS NOT NULL AND (" + current + comparison
                    + reference + " OR " + current + " IS NULL)");
            terms.add("(" + String.join(" AND ", conditions) + ")");
            equalPrefix.add(current + " IS NOT DISTINCT FROM " + reference);
        }
        List<String> identityConditions = new ArrayList<>(equalPrefix);
        identityConditions.add("current_row._object_id>:cursor");
        terms.add("(" + String.join(" AND ", identityConditions) + ")");
        return String.join(" OR ", terms);
    }

    private String aggregateExpression(ObjectSchema schema, AggregateMetric metric) {
        String operation = metric.operation().toUpperCase(java.util.Locale.ROOT);
        if ("COUNT".equals(operation) && (metric.propertyId() == null
                || metric.propertyId().isBlank())) {
            return "count(*)";
        }
        PropertySchema property = property(schema, metric.propertyId());
        String column = identifiers.quote(property.physicalKey());
        if (List.of("AVG", "SUM").contains(operation)
                && !List.of("DECIMAL", "INTEGER", "LONG").contains(property.valueType())) {
            throw new ObjectInstanceStoreException(
                    "AGGREGATE_TYPE_INVALID", operation + " requires a numeric property");
        }
        return switch (operation) {
            case "AVG" -> "avg(" + column + ")";
            case "COUNT" -> "count(" + column + ")";
            case "DISTINCT_COUNT" -> "count(DISTINCT " + column + ")";
            case "MAX" -> "max(" + column + ")";
            case "MIN" -> "min(" + column + ")";
            case "SUM" -> "sum(" + column + ")";
            default -> throw new ObjectInstanceStoreException(
                    "AGGREGATE_OPERATION_INVALID", "Unsupported aggregate operation");
        };
    }

    private PropertySchema property(ObjectSchema schema, String name) {
        PropertySchema property = schema.property(name);
        if (property == null) {
            throw new ObjectInstanceStoreException(
                    "PROPERTY_UNKNOWN", "Unknown property: " + name);
        }
        return property;
    }

    private DynamicColumns columns(
            ObjectSchema schema, JsonNode basePayload, JsonNode overridePayload) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        int index = 0;
        for (PropertySchema property : schema.properties()) {
            String parameter = "property" + index++;
            JsonNode value = overridePayload.has(property.physicalKey())
                    ? overridePayload.get(property.physicalKey())
                    : basePayload.get(property.physicalKey());
            names.add(identifiers.quote(property.physicalKey()));
            values.add(valueExpression(parameter, property.valueType()));
            assignments.add(identifiers.quote(property.physicalKey()) + "="
                    + valueExpression(parameter, property.valueType()));
            parameters.put(parameter, jdbcValue(value, property.valueType()));
        }
        return new DynamicColumns(
                names.isEmpty() ? "" : "," + String.join(",", names),
                values.isEmpty() ? "" : "," + String.join(",", values),
                assignments.isEmpty() ? "" : "," + String.join(",", assignments),
                parameters);
    }

    private MapSqlParameterSource commonParameters(
            String objectId,
            String title,
            JsonNode basePayload,
            JsonNode overridePayload,
            String sourceKind,
            String sourceRef,
            String sourceRevision) {
        return new MapSqlParameterSource()
                .addValue("objectId", objectId)
                .addValue("title", title)
                .addValue("basePayload", basePayload.toString())
                .addValue("overridePayload", overridePayload.toString())
                .addValue("sourceKind", sourceKind)
                .addValue("sourceRef", sourceRef)
                .addValue("sourceRevision", sourceRevision);
    }

    private Object jdbcValue(JsonNode value, String valueType) {
        if (value == null || value.isNull()) {
            return null;
        }
        return switch (valueType) {
            case "BOOLEAN" -> value.booleanValue();
            case "DECIMAL" -> value.decimalValue();
            case "INTEGER" -> value.intValue();
            case "LONG" -> value.longValue();
            case "DATE" -> LocalDate.parse(value.asText());
            case "DATETIME" -> OffsetDateTime.parse(value.asText());
            case "INTEGER_ARRAY", "JSON", "STRING_ARRAY" -> value.toString();
            default -> value.asText();
        };
    }

    private String valueExpression(String parameter, String valueType) {
        return switch (valueType) {
            case "INTEGER_ARRAY", "JSON", "STRING_ARRAY" -> "CAST(:" + parameter + " AS jsonb)";
            default -> ":" + parameter;
        };
    }

    private StoredInstance instance(ResultSet row, int number) throws SQLException {
        return new StoredInstance(
                row.getString("_object_id"),
                row.getString("_title"),
                row.getLong("_version"),
                readJson(row.getString("_base_payload")),
                readJson(row.getString("_override_payload")),
                row.getString("_source_kind"),
                row.getString("_source_ref"),
                row.getString("_source_revision"),
                instant(row.getTimestamp("_created_at")),
                instant(row.getTimestamp("_updated_at")),
                instant(row.getTimestamp("_deleted_at")));
    }

    private JsonNode readJson(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored instance payload is invalid", failure);
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record DynamicColumns(
            String names, String values, String assignments, Map<String, Object> parameters) {}

    public record IdempotencyRecord(String requestHash, String objectId, long version) {}

    public record BulkIdempotencyRecord(String requestHash, String response) {}
}
