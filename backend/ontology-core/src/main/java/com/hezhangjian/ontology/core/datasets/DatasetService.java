package com.hezhangjian.ontology.core.datasets;

import static com.hezhangjian.ontology.core.datasets.DatasetModels.*;
import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.core.connections.ConnectionProblem;
import com.hezhangjian.ontology.core.connections.ConnectionProperties;
import com.hezhangjian.ontology.core.pipelines.PipelineService;
import com.hezhangjian.ontology.core.security.WorkspaceContext;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetService {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ConnectionProperties connections;
    private final DatasetStorageClient storage;
    private final PipelineService pipelines;

    public DatasetService(JdbcClient jdbc, ObjectMapper json, ConnectionProperties connections,
                          DatasetStorageClient storage, PipelineService pipelines) {
        this.jdbc = jdbc;
        this.json = json;
        this.connections = connections;
        this.storage = storage;
        this.pipelines = pipelines;
    }

    public DatasetPage list(String search) {
        String term = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Dataset> items = jdbc.sql("""
                SELECT d.*,p.name pipeline_name FROM control.datasets d JOIN control.pipelines p ON p.id=d.pipeline_id
                WHERE d.ontology_id=:ontology AND (:term='' OR lower(d.name) LIKE '%' || :term || '%') ORDER BY d.updated_at DESC
                """).param("ontology", WorkspaceContext.id()).param("term", term).query(this::mapDataset).list();
        return new DatasetPage(items, items.size());
    }

    public Dataset get(UUID id) {
        return jdbc.sql("SELECT d.*,p.name pipeline_name FROM control.datasets d JOIN control.pipelines p ON p.id=d.pipeline_id WHERE d.id=:id AND d.ontology_id=:ontology")
                .param("id", id).param("ontology", WorkspaceContext.id()).query(this::mapDataset).optional()
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "数据集不存在"));
    }

    public Preview preview(UUID id, int limit, int offset) {
        Dataset dataset = get(id);
        List<Map<String, Object>> all = rows(id);
        int start = Math.min(Math.max(offset, 0), all.size());
        int end = Math.min(start + Math.min(Math.max(limit, 1), 200), all.size());
        List<Map<String, Object>> rows = all.subList(start, end);
        return new Preview(dataset.fields().stream().map(Field::name).toList(), rows, dataset.rowCount());
    }

    @Transactional
    public Dataset materialize(UUID pipelineId, MaterializeRequest request, Actor actor) {
        Map<String, Object> pipeline = jdbc.sql("SELECT p.*,d.graph::text graph FROM control.pipelines p JOIN control.pipeline_drafts d ON d.pipeline_id=p.id WHERE p.id=:id AND p.ontology_id=:ontology")
                .param("id", pipelineId).param("ontology", WorkspaceContext.id()).query(this::mapRow).list().stream().findFirst().orElseThrow(() -> new ConnectionProblem("PIPELINE_NOT_FOUND", "管道不存在或没有可运行草稿"));
        PipelineGraph graph = read(String.valueOf(pipeline.get("graph")), PipelineGraph.class);
        List<PipelineNode> outputs = graph.nodes().stream()
                .filter(node -> "DATASET_OUTPUT".equals(node.type())).toList();
        if (outputs.isEmpty()) throw new ConnectionProblem(
                "DATASET_OUTPUT_REQUIRED", "管道至少需要一个数据集输出节点");
        List<UUID> datasetIds = new ArrayList<>();
        for (PipelineNode output : outputs) {
            boolean onlyOutput = outputs.size() == 1;
            String name = first(onlyOutput && request != null ? request.name() : null,
                    string(output.config().get("datasetName")),
                    String.valueOf(pipeline.get("name")) + " - " + output.name());
            String description = first(onlyOutput && request != null ? request.description() : null,
                    string(output.config().get("description")), "由管道生成");
            UUID datasetId = jdbc.sql("""
                    SELECT id FROM control.datasets WHERE pipeline_id=:id AND output_node_id=:node
                    """).param("id", pipelineId).param("node", output.id())
                    .query(UUID.class).optional().orElse(UUID.randomUUID());
            jdbc.sql("""
                    INSERT INTO control.datasets(
                      id,ontology_id,name,normalized_name,description,pipeline_id,output_node_id,
                      status,owner_id,owner_name)
                    VALUES (:id,:ontology,:name,:normalized,:description,:pipeline,:node,
                      'BUILDING',:ownerId,:ownerName)
                    ON CONFLICT(id) DO UPDATE
                      SET name=excluded.name,normalized_name=excluded.normalized_name,
                      description=excluded.description,schema='[]'::jsonb,row_count=0,
                      status='BUILDING',updated_at=now()
                    """).param("id", datasetId).param("ontology", WorkspaceContext.id())
                    .param("name", name.trim()).param("normalized", normalize(name))
                    .param("description", description).param("pipeline", pipelineId)
                    .param("node", output.id()).param("ownerId", actor.id())
                    .param("ownerName", actor.name()).update();
            datasetIds.add(datasetId);
        }

        Pipeline current = pipelines.get(pipelineId);
        boolean draftChanged = current.publishedVersion() == null
                || Boolean.TRUE.equals(pipelines.diff(pipelineId).get("changed"));
        if (draftChanged) {
            pipelines.publish(pipelineId, new PublishRequest(true, true, null), actor);
        } else {
            pipelines.run(pipelineId, actor);
        }
        return get(datasetIds.getFirst());
    }

    @Transactional
    void finalizeFlinkMaterialization(UUID datasetId, List<Map<String, Object>> rows) {
        List<Field> fields = inferFields(rows);
        String objectKey = storage.store(datasetId, rows);
        jdbc.sql("DELETE FROM control.dataset_rows WHERE dataset_id=:id").param("id", datasetId).update();
        jdbc.sql("UPDATE control.datasets SET schema=:schema::jsonb,row_count=:count,object_key=:objectKey,status='READY',updated_at=now() WHERE id=:id")
                .param("schema", write(fields)).param("count", rows.size()).param("objectKey", objectKey).param("id", datasetId).update();
    }

    @Transactional
    void finalizeFlinkMaterialization(UUID datasetId, String correlationId, long expectedRows) {
        List<Map<String, Object>> sample = jdbc.sql("""
                SELECT payload::text FROM control.dataset_materialization_rows
                WHERE dataset_id=:datasetId AND correlation_id=:correlation ORDER BY event_id LIMIT 1000
                """).param("datasetId", datasetId).param("correlation", correlationId)
                .query((row, number) -> readMap(row.getString(1))).list();
        DatasetStorageClient.StoreResult stored;
        try (Stream<Map<String, Object>> rows = jdbc.sql("""
                SELECT payload::text FROM control.dataset_materialization_rows
                WHERE dataset_id=:datasetId AND correlation_id=:correlation ORDER BY event_id
                """).param("datasetId", datasetId).param("correlation", correlationId)
                .query((row, number) -> readMap(row.getString(1))).stream()) {
            stored = storage.store(datasetId, rows::iterator, expectedRows);
        }
        jdbc.sql("DELETE FROM control.dataset_rows WHERE dataset_id=:id").param("id", datasetId).update();
        jdbc.sql("""
                UPDATE control.datasets
                SET schema=:schema::jsonb,row_count=:count,object_key=:objectKey,status='READY',updated_at=now()
                WHERE id=:id
                """).param("schema", write(inferFields(sample))).param("count", stored.rowCount())
                .param("objectKey", stored.objectKey()).param("id", datasetId).update();
    }

    public QueryResult query(UUID id, QueryRequest request) {
        get(id);
        List<Dimension> dimensionSpecs = dimensions(request);
        List<String> dimensions = dimensionSpecs.stream().map(Dimension::label).toList();
        List<Metric> metrics = request == null || request.metrics() == null ? List.of() : request.metrics().stream().limit(8).toList();
        List<Map<String, Object>> source = rows(id);
        List<Map<String, Object>> filtered = source.stream().filter(row -> matches(row, request == null ? null : request.filters())).toList();
        Map<List<String>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : filtered) {
            List<String> key = dimensionSpecs.stream().map(dimension -> dimensionValue(row, dimension)).toList();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        if (dimensions.isEmpty()) groups.putIfAbsent(List.of(), filtered);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<List<String>, List<Map<String, Object>>> group : groups.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (int index = 0; index < dimensions.size(); index++) item.put(dimensions.get(index), group.getKey().get(index));
            for (Metric metric : metrics) item.put(metric.label() == null || metric.label().isBlank() ? metric.operation() : metric.label(), calculate(group.getValue(), metric));
            result.add(item);
        }
        String orderBy = request == null ? null : request.orderBy();
        if (orderBy != null && !orderBy.isBlank()) result.sort((left, right) -> compare(left.get(orderBy), right.get(orderBy)) * ("ASC".equalsIgnoreCase(request.orderDirection()) ? 1 : -1));
        int limit = request == null || request.limit() == null ? 500 : Math.min(Math.max(request.limit(), 1), 2000);
        return new QueryResult(dimensions, metrics.stream().map(metric -> metric.label() == null || metric.label().isBlank() ? metric.operation() : metric.label()).toList(), result.stream().limit(limit).toList(), filtered.size());
    }

    public MappingPreview mappingPreview(UUID id, String identityField, String titleField) {
        List<Map<String, Object>> rows = rows(id);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        long empty = 0;
        for (Map<String, Object> row : rows) {
            String key = string(row.get(identityField));
            if (key.isBlank()) { empty++; continue; }
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        long conflicts = grouped.values().stream().filter(group -> group.stream().map(row -> string(row.get(titleField))).filter(value -> !value.isBlank()).distinct().count() > 1).count();
        List<Map<String, Object>> samples = grouped.values().stream().limit(12).map(group -> group.get(0)).toList();
        return new MappingPreview(identityField, titleField, rows.size(), grouped.size(), empty,
                Math.max(0, rows.size() - empty - grouped.size()), conflicts, samples);
    }

    @Transactional
    public void delete(UUID id) {
        get(id);
        Long dashboardDependencies = jdbc.sql("SELECT count(*) FROM control.dashboard_data_sources WHERE dataset_id=:id")
                .param("id", id).query(Long.class).single();
        if (dashboardDependencies != null && dashboardDependencies > 0) {
            throw new ConnectionProblem("DATASET_IN_USE", "Dataset 正被分析看板使用，请先移除对应数据源");
        }
        Long activeRuns = jdbc.sql("""
                SELECT count(*) FROM control.pipeline_runs r JOIN control.datasets d ON d.pipeline_id=r.pipeline_id
                WHERE d.id=:id AND r.status IN ('COMPILING','PROJECTING','QUEUED','READING','RUNNING','STARTING','SUBMITTED','TRANSFORMING','PUBLISHING')
                """).param("id", id).query(Long.class).single();
        if (activeRuns != null && activeRuns > 0) {
            throw new ConnectionProblem("DATASET_BUILD_ACTIVE", "Flink 物化任务仍在运行，请等待完成或先取消运行");
        }
        String objectKey = jdbc.sql("SELECT object_key FROM control.datasets WHERE id=:id")
                .param("id", id).query(String.class).optional().orElse(null);
        storage.delete(id, objectKey);
        jdbc.sql("DELETE FROM control.dataset_rows WHERE dataset_id=:id").param("id", id).update();
        jdbc.sql("DELETE FROM control.datasets WHERE id=:id").param("id", id).update();
    }

    private List<Map<String, Object>> executeGraph(PipelineGraph graph, UUID sourceAssetId) {
        List<PipelineNode> order = topological(graph);
        List<Map<String, Object>> rows = readCsv(sourceAssetId);
        for (PipelineNode node : order) {
            if ("SELECT".equals(node.type())) rows = select(rows, node.config());
            else if ("JOIN".equals(node.type())) rows = join(rows, node.config());
            else if ("DERIVE".equals(node.type())) rows = derive(rows, node.config());
            else if ("FILTER".equals(node.type())) rows = filter(rows, node.config());
            else if ("DEDUPLICATE".equals(node.type())) rows = deduplicate(rows, node.config());
        }
        return rows;
    }

    private List<Map<String, Object>> readCsv(UUID assetId) {
        Map<String, Object> asset = jdbc.sql("""
                SELECT a.full_path,d.config::text config FROM control.data_source_assets a
                JOIN control.data_sources d ON d.id=a.data_source_id WHERE a.id=:id
                """).param("id", assetId).query(this::mapRow).list().stream().findFirst().orElseThrow(() -> new ConnectionProblem("DATASET_SOURCE_MISSING", "数据源文件不存在"));
        Map<String, Object> config = readMap(String.valueOf(asset.get("config")));
        String path = String.valueOf(asset.get("full_path"));
        int slash = path.indexOf('/');
        if (slash < 1) throw new ConnectionProblem("DATASET_SOURCE_INVALID", "当前数据集物化仅支持 CSV 文件资产");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(MinioClient.builder()
                .endpoint(String.valueOf(config.get("endpoint")))
                .credentials(connections.localCsvAccessKey(), connections.localCsvSecretKey()).build()
                .getObject(GetObjectArgs.builder().bucket(path.substring(0, slash)).object(path.substring(slash + 1)).build()), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) return List.of();
            List<String> columns = csv(header);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
                List<String> values = csv(line);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int index = 0; index < columns.size(); index++) row.put(columns.get(index), scalar(index < values.size() ? values.get(index) : ""));
                rows.add(row);
            }
            return rows;
        } catch (Exception cause) {
            throw new ConnectionProblem("DATASET_SOURCE_READ_FAILED", "无法读取 CSV 数据，请检查连接和文件编码");
        }
    }

    private List<Map<String, Object>> select(List<Map<String, Object>> rows, Map<String, Object> config) {
        Object raw = config.get("fields");
        if (!(raw instanceof List<?> fields) || fields.isEmpty()) return rows;
        return rows.stream().map(row -> {
            Map<String, Object> selected = new LinkedHashMap<>();
            for (Object item : fields) if (item instanceof Map<?, ?> field) {
                String source = string(field.get("source")); String target = first(string(field.get("target")), source);
                if (row.containsKey(source)) selected.put(target, row.get(source));
            }
            return selected;
        }).toList();
    }

    private List<Map<String, Object>> join(List<Map<String, Object>> rows, Map<String, Object> config) {
        UUID lookupAsset = UUID.fromString(string(config.get("lookupAssetId")));
        String leftKey = string(config.get("leftKey")); String rightKey = string(config.get("rightKey"));
        String prefix = first(string(config.get("lookupPrefix")), "辅助_");
        Map<String, Map<String, Object>> lookup = new LinkedHashMap<>();
        for (Map<String, Object> row : readCsv(lookupAsset)) lookup.putIfAbsent(string(row.get(rightKey)), row);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> match = lookup.get(string(row.get(leftKey)));
            if (match == null && "INNER".equals(config.get("joinType"))) continue;
            Map<String, Object> merged = new LinkedHashMap<>(row);
            if (match != null) for (Map.Entry<String, Object> value : match.entrySet()) merged.put(merged.containsKey(value.getKey()) ? prefix + value.getKey() : value.getKey(), value.getValue());
            result.add(merged);
        }
        return result;
    }

    private List<Map<String, Object>> derive(List<Map<String, Object>> rows, Map<String, Object> config) {
        String name = string(config.get("name")); String operation = string(config.get("operation"));
        return rows.stream().map(row -> { Map<String, Object> copy = new LinkedHashMap<>(row);
            Object value = "CONCAT".equals(operation) ? string(row.get(string(config.get("left")))) + string(config.get("separator")) + string(row.get(string(config.get("right"))))
                    : "COALESCE".equals(operation) ? first(string(row.get(string(config.get("left")))), string(row.get(string(config.get("right"))))) : config.get("value");
            copy.put(name, value); return copy; }).toList();
    }

    private List<Map<String, Object>> filter(List<Map<String, Object>> rows, Map<String, Object> config) {
        String field = string(config.get("field")); String operation = string(config.get("operator")); String expected = string(config.get("value"));
        return rows.stream().filter(row -> { String actual = string(row.get(field)); return switch (operation) {
            case "NOT_EQUALS" -> !actual.equals(expected); case "CONTAINS" -> actual.contains(expected);
            case "IS_NULL" -> actual.isBlank(); case "IS_NOT_NULL" -> !actual.isBlank(); default -> actual.equals(expected); }; }).toList();
    }

    private List<Map<String, Object>> deduplicate(List<Map<String, Object>> rows, Map<String, Object> config) {
        List<String> keys = config.get("keys") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        Set<String> seen = new LinkedHashSet<>();
        return rows.stream().filter(row -> seen.add(keys.stream().map(key -> string(row.get(key))).toList().toString())).toList();
    }

    private List<Map<String, Object>> applyOutputMappings(List<Map<String, Object>> rows, Map<String, Object> config) {
        if (!(config.get("fieldMappings") instanceof List<?> mappings) || mappings.isEmpty()) return rows;
        return rows.stream().map(row -> { Map<String, Object> output = new LinkedHashMap<>();
            for (Object item : mappings) if (item instanceof Map<?, ?> mapping) {
                String source = string(mapping.get("source")); String target = string(mapping.get("target"));
                if (!target.isBlank()) output.put(target, row.get(source));
            }
            return output; }).toList();
    }

    private List<PipelineNode> topological(PipelineGraph graph) {
        Map<String, PipelineNode> nodes = new LinkedHashMap<>(); graph.nodes().forEach(node -> nodes.put(node.id(), node));
        Map<String, Integer> incoming = new HashMap<>(); nodes.keySet().forEach(id -> incoming.put(id, 0));
        Map<String, List<String>> outgoing = new HashMap<>();
        for (PipelineEdge edge : graph.edges()) { incoming.merge(edge.target(), 1, Integer::sum); outgoing.computeIfAbsent(edge.source(), ignored -> new ArrayList<>()).add(edge.target()); }
        ArrayDeque<String> queue = new ArrayDeque<>(); incoming.forEach((id, count) -> { if (count == 0) queue.add(id); });
        List<PipelineNode> result = new ArrayList<>();
        while (!queue.isEmpty()) { String id = queue.remove(); result.add(nodes.get(id)); for (String target : outgoing.getOrDefault(id, List.of())) if (incoming.merge(target, -1, Integer::sum) == 0) queue.add(target); }
        return result;
    }

    private List<Field> inferFields(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        Set<String> fieldNames = new LinkedHashSet<>();
        rows.forEach(row -> fieldNames.addAll(row.keySet()));
        return fieldNames.stream().map(name -> {
            List<Object> values = rows.stream().map(row -> row.get(name)).filter(Objects::nonNull).limit(3).toList();
            Object sample = values.stream().findFirst().orElse("");
            String type = sample instanceof Number ? (sample instanceof BigDecimal ? "DECIMAL" : "INTEGER") : inferredTextType(sample);
            boolean nullable = rows.stream().anyMatch(row -> row.get(name) == null || string(row.get(name)).isBlank());
            return new Field(name, type, nullable, values);
        }).toList();
    }

    private Map<String, Object> mapRow(ResultSet row, int number) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        java.sql.ResultSetMetaData metadata = row.getMetaData();
        for (int index = 1; index <= metadata.getColumnCount(); index++) values.put(metadata.getColumnLabel(index), row.getObject(index));
        return values;
    }

    private Object calculate(List<Map<String, Object>> rows, Metric metric) {
        String operation = string(metric.operation()).toUpperCase(Locale.ROOT);
        if ("COUNT".equals(operation)) return rows.size();
        if ("DISTINCT_COUNT".equals(operation)) return rows.stream().map(row -> string(row.get(metric.field()))).filter(value -> !value.isBlank()).distinct().count();
        List<BigDecimal> numbers = rows.stream().map(row -> decimal(row.get(metric.field()))).filter(Objects::nonNull).toList();
        BigDecimal sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if ("SUM_PER_DISTINCT".equals(operation)) {
            long denominator = rows.stream().map(row -> string(row.get(metric.distinctField()))).filter(value -> !value.isBlank()).distinct().count();
            return denominator == 0 ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(denominator), 2, java.math.RoundingMode.HALF_UP);
        }
        if ("AVG".equals(operation)) return numbers.isEmpty() ? BigDecimal.ZERO : sum.divide(BigDecimal.valueOf(numbers.size()), 2, java.math.RoundingMode.HALF_UP);
        if ("MIN".equals(operation)) return numbers.stream().min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        if ("MAX".equals(operation)) return numbers.stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        return sum;
    }

    private List<Dimension> dimensions(QueryRequest request) {
        if (request == null) return List.of();
        if (request.dimensionSpecs() != null && !request.dimensionSpecs().isEmpty()) {
            return request.dimensionSpecs().stream()
                    .filter(value -> value != null && !string(value.field()).isBlank())
                    .map(value -> new Dimension(value.field(), string(value.label()).isBlank() ? value.field() : value.label(), value.timeGrain()))
                    .limit(3).toList();
        }
        if (request.dimensions() == null) return List.of();
        return request.dimensions().stream().filter(value -> !string(value).isBlank())
                .limit(3).map(value -> new Dimension(value, value, null)).toList();
    }

    private String dimensionValue(Map<String, Object> row, Dimension dimension) {
        String value = string(row.get(dimension.field()));
        String grain = string(dimension.timeGrain()).toUpperCase(Locale.ROOT);
        if (grain.isBlank() || "NONE".equals(grain)) return value;
        LocalDateTime dateTime = dateTime(value);
        if (dateTime == null) return value;
        return switch (grain) {
            case "YEAR" -> String.format("%04d", dateTime.getYear());
            case "QUARTER" -> dateTime.getYear() + "-Q" + ((dateTime.getMonthValue() - 1) / 3 + 1);
            case "MONTH" -> YearMonth.from(dateTime).toString();
            case "WEEK" -> dateTime.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString();
            case "DAY" -> dateTime.toLocalDate().toString();
            default -> value;
        };
    }

    private LocalDateTime dateTime(String value) {
        if (value.isBlank()) return null;
        try { return OffsetDateTime.parse(value).toLocalDateTime(); } catch (java.time.format.DateTimeParseException ignored) { }
        try { return Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDateTime(); } catch (java.time.format.DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value); } catch (java.time.format.DateTimeParseException ignored) { }
        try { return LocalDate.parse(value).atStartOfDay(); } catch (java.time.format.DateTimeParseException ignored) { }
        try { return YearMonth.parse(value).atDay(1).atStartOfDay(); } catch (java.time.format.DateTimeParseException ignored) { }
        return null;
    }

    private String inferredTextType(Object sample) {
        return dateTime(string(sample)) == null ? "TEXT" : "DATETIME";
    }

    private List<Map<String, Object>> rows(UUID id) {
        List<Map<String, Object>> projected = storage.rows(id);
        if (!projected.isEmpty() || get(id).rowCount() == 0) return projected;
        return jdbc.sql("SELECT body FROM control.dataset_rows WHERE dataset_id=:id ORDER BY row_number")
                .param("id", id).query((row, number) -> readMap(row.getString("body"))).list();
    }

    private boolean matches(Map<String, Object> row, List<Filter> filters) {
        if (filters == null) return true;
        for (Filter filter : filters) { String actual = string(row.get(filter.field())); List<String> values = filter.values() == null ? List.of() : filter.values();
            if ("IN".equalsIgnoreCase(filter.operator()) && !values.contains(actual)) return false;
            if ("NOT_IN".equalsIgnoreCase(filter.operator()) && values.contains(actual)) return false;
            if ("EQUALS".equalsIgnoreCase(filter.operator()) && (values.isEmpty() || !actual.equals(values.get(0)))) return false;
            if ("FIELD_EQUALS".equalsIgnoreCase(filter.operator())) {
                Object left = row.get(filter.field());
                Object right = row.get(filter.comparisonField());
                if (left == null || right == null || !string(left).equals(string(right))) return false;
            }
        }
        return true;
    }

    private int compare(Object left, Object right) { BigDecimal a = decimal(left); BigDecimal b = decimal(right); return a != null && b != null ? a.compareTo(b) : string(left).compareTo(string(right)); }
    private BigDecimal decimal(Object value) { try { return value == null || string(value).isBlank() ? null : new BigDecimal(string(value)); } catch (NumberFormatException ignored) { return null; } }
    private Object scalar(String value) { String trimmed = value.trim(); try { if (trimmed.matches("-?\\d+")) return Long.parseLong(trimmed); if (trimmed.matches("-?\\d+\\.\\d+")) return new BigDecimal(trimmed); } catch (NumberFormatException ignored) { } return trimmed; }
    private List<String> csv(String line) { String value = line.startsWith("\uFEFF") ? line.substring(1) : line; List<String> result = new ArrayList<>(); StringBuilder current = new StringBuilder(); boolean quoted = false; for (int i=0;i<value.length();i++){ char c=value.charAt(i); if(c=='\"'){ if(quoted && i+1<value.length() && value.charAt(i+1)=='\"'){current.append('\"');i++;} else quoted=!quoted; } else if(c==','&&!quoted){result.add(current.toString().trim());current.setLength(0);} else current.append(c);} result.add(current.toString().trim()); return result; }
    private Dataset mapDataset(ResultSet row, int number) throws SQLException { return new Dataset(row.getObject("id", UUID.class), row.getString("name"), row.getString("description"), row.getObject("pipeline_id", UUID.class), row.getString("pipeline_name"), read(row.getString("schema"), new TypeReference<List<Field>>(){}), row.getLong("row_count"), row.getString("status"), row.getString("owner_name"), instant(row,"created_at"), instant(row,"updated_at")); }
    private Instant instant(ResultSet row, String column) throws SQLException { Timestamp value=row.getTimestamp(column); return value==null?null:value.toInstant(); }
    private String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    private String first(String... values) { for(String value:values) if(value!=null&&!value.isBlank()) return value; return ""; }
    private String string(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception cause) { throw new IllegalStateException(cause); } }
    private <T> T read(String value, Class<T> type) { try { return json.readValue(value,type); } catch(Exception cause){ throw new IllegalStateException(cause); } }
    private <T> T read(String value, TypeReference<T> type) { try { return json.readValue(value,type); } catch(Exception cause){ throw new IllegalStateException(cause); } }
    private Map<String,Object> readMap(String value) { return read(value,new TypeReference<Map<String,Object>>(){}); }
}
