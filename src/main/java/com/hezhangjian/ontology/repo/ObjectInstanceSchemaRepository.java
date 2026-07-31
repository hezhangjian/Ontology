package com.hezhangjian.ontology.repo;

import static com.hezhangjian.ontology.instance.ObjectInstanceModels.ObjectSchema;
import static com.hezhangjian.ontology.instance.ObjectInstanceModels.PropertySchema;

import com.hezhangjian.ontology.instance.ObjectInstanceStoreException;
import com.hezhangjian.ontology.instance.SqlIdentifierResolver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ObjectInstanceSchemaRepository {
    private final JdbcTemplate jdbc;
    private final SqlIdentifierResolver identifiers;

    public ObjectInstanceSchemaRepository(
            DataSource dataSource, SqlIdentifierResolver identifiers) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.identifiers = identifiers;
    }

    public ObjectSchema resolve(UUID ontologyId, String objectTypeApiName) {
        SchemaHead head = jdbc.query(
                        """
                        SELECT r.id, r.api_name, r.physical_key,
                               COALESCE(r.active_version, r.latest_version) selected_version
                        FROM control.ontology_resources r
                        WHERE r.ontology_id=? AND r.kind='OBJECT_TYPE'
                          AND r.api_name=?
                        """,
                        (row, number) -> new SchemaHead(
                                row.getObject("id", UUID.class),
                                row.getString("api_name"),
                                row.getString("physical_key"),
                                row.getInt("selected_version")),
                        ontologyId,
                        objectTypeApiName)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "OBJECT_TYPE_NOT_FOUND", "Object type does not exist"));
        List<PropertySchema> properties = jdbc.query(
                """
                SELECT p.id,p.api_name,p.physical_key,pv.display_name,pv.value_type,pv.required,
                       pv.primary_key,pv.title_property,pv.searchable,pv.sensitive
                FROM control.properties p
                JOIN control.ontology_resource_versions rv
                  ON rv.resource_id=p.object_type_id AND rv.version=?
                JOIN control.property_versions pv
                  ON pv.property_id=p.id AND pv.object_type_version_id=rv.id
                WHERE p.object_type_id=?
                ORDER BY p.api_name
                """,
                this::property,
                head.version(),
                head.id());
        PropertySchema primary = properties.stream()
                .filter(PropertySchema::primaryKey)
                .findFirst()
                .orElseThrow(() -> new ObjectInstanceStoreException(
                        "PRIMARY_KEY_REQUIRED", "Object type must define one primary key"));
        PropertySchema title = properties.stream()
                .filter(PropertySchema::titleProperty)
                .findFirst()
                .orElse(primary);
        return new ObjectSchema(
                ontologyId,
                head.id(),
                head.apiName(),
                head.physicalKey(),
                primary,
                title,
                properties,
                "instance",
                identifiers.tableName(head.physicalKey()));
    }

    public void ensureTable(ObjectSchema schema) {
        String table = identifiers.qualified(schema.schemaName(), schema.tableName());
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    _object_id text PRIMARY KEY,
                    _title text NOT NULL,
                    _version bigint NOT NULL,
                    _base_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
                    _override_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
                    _source_kind varchar(24),
                    _source_ref varchar(512),
                    _source_revision varchar(240),
                    _created_at timestamptz NOT NULL DEFAULT now(),
                    _updated_at timestamptz NOT NULL DEFAULT now(),
                    _deleted_at timestamptz
                )
                """.formatted(table));
        for (PropertySchema property : schema.properties()) {
            String column = identifiers.quote(property.physicalKey());
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS "
                    + column + " " + sqlType(property.valueType()));
            if (property.primaryKey()) {
                jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS "
                        + identifiers.quote(indexName(schema.tableName(), property.physicalKey()))
                        + " ON " + table + " (" + column + ") WHERE _deleted_at IS NULL");
            } else if (property.searchable() && !property.sensitive()) {
                jdbc.execute("CREATE INDEX IF NOT EXISTS "
                        + identifiers.quote(indexName(schema.tableName(), property.physicalKey()))
                        + " ON " + table + " (" + column + ")");
            }
        }
        jdbc.update(
                """
                INSERT INTO control.object_instance_table_registry(
                  ontology_id,object_type_id,object_type_api_name,object_type_physical_key,
                  schema_name,table_name,status)
                VALUES (?,?,?,?,?,?,'READY')
                ON CONFLICT(ontology_id,object_type_id) DO UPDATE
                SET object_type_api_name=excluded.object_type_api_name,
                    object_type_physical_key=excluded.object_type_physical_key,
                    schema_name=excluded.schema_name,
                    table_name=excluded.table_name,
                    status='READY',
                    updated_at=CASE
                      WHEN control.object_instance_table_registry.object_type_api_name
                             IS DISTINCT FROM excluded.object_type_api_name
                        OR control.object_instance_table_registry.object_type_physical_key
                             IS DISTINCT FROM excluded.object_type_physical_key
                        OR control.object_instance_table_registry.schema_name
                             IS DISTINCT FROM excluded.schema_name
                        OR control.object_instance_table_registry.table_name
                             IS DISTINCT FROM excluded.table_name
                        OR control.object_instance_table_registry.status<>'READY'
                      THEN now()
                      ELSE control.object_instance_table_registry.updated_at
                    END
                """,
                schema.ontologyId(),
                schema.objectTypeId(),
                schema.objectTypeApiName(),
                schema.objectTypePhysicalKey(),
                schema.schemaName(),
                schema.tableName());
    }

    private PropertySchema property(ResultSet row, int number) throws SQLException {
        return new PropertySchema(
                row.getObject("id", UUID.class),
                row.getString("api_name"),
                row.getString("display_name"),
                row.getString("physical_key"),
                row.getString("value_type"),
                row.getBoolean("required"),
                row.getBoolean("primary_key"),
                row.getBoolean("title_property"),
                row.getBoolean("searchable"),
                row.getBoolean("sensitive"));
    }

    private String sqlType(String valueType) {
        return switch (valueType) {
            case "BOOLEAN" -> "boolean";
            case "DATE" -> "date";
            case "DATETIME" -> "timestamptz";
            case "DECIMAL" -> "numeric";
            case "INTEGER" -> "integer";
            case "LONG" -> "bigint";
            case "INTEGER_ARRAY", "JSON", "STRING_ARRAY" -> "jsonb";
            default -> "text";
        };
    }

    private String indexName(String table, String column) {
        String value = "ix_" + table + "_" + column;
        return value.length() <= 63 ? value : value.substring(0, 63);
    }

    private record SchemaHead(UUID id, String apiName, String physicalKey, int version) {}
}
