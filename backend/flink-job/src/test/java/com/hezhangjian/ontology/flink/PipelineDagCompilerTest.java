package com.hezhangjian.ontology.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;

class PipelineDagCompilerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keepsBranchesIndependentAndEmitsEveryOutput() throws Exception {
        StreamExecutionEnvironment environment = environment();
        DataStream<String> source = environment.fromElements(
                "{\"id\":\"e-1\",\"name\":\"Alice\",\"ts\":\"2026-07-23T00:00:00Z\"}");
        Map<String, Object> graph = graph(
                List.of(
                        node("source", "SOURCE", Map.of()),
                        node("select", "SELECT", Map.of("fields", List.of(
                                Map.of("source", "id", "target", "id")))),
                        node("derive", "DERIVE", Map.of(
                                "name", "branch", "operation", "CONSTANT", "value", "right")),
                        node("output-a", "DATASET_OUTPUT", Map.of(
                                "datasetId", "dataset-a", "datasetName", "A",
                                "fieldMappings", List.of(Map.of("source", "id", "target", "id")),
                                "fieldSelectionMode", "EXPLICIT")),
                        node("output-b", "DATASET_OUTPUT", Map.of(
                                "datasetId", "dataset-b", "datasetName", "B",
                                "fieldMappings", List.of(Map.of("source", "branch", "target", "branch")),
                                "fieldSelectionMode", "EXPLICIT"))),
                List.of(
                        edge("source", "select"), edge("source", "derive"),
                        edge("select", "output-a"), edge("derive", "output-b")));

        List<Map<String, Object>> events = collect(PipelineDagCompiler.compile(
                Map.of("source", PipelineDagCompiler.assignWatermarks(source, Map.of("eventTimeField", "ts"))),
                graph, sourceConfig(), "dag:branches", null));

        assertEquals(2, events.size());
        assertEquals(java.util.Set.of("dataset-a", "dataset-b"),
                events.stream().map(event -> String.valueOf(event.get("dataset_id")))
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of(Map.of("id", "e-1"), Map.of("branch", "right")),
                new java.util.HashSet<>(events.stream().map(event -> map(event.get("payload"))).toList()));
    }

    @Test
    void joinsTwoIndependentSourcesInEventTimeWindow() throws Exception {
        StreamExecutionEnvironment environment = environment();
        Map<String, DataStream<String>> sources = new LinkedHashMap<>();
        sources.put("employees", PipelineDagCompiler.assignWatermarks(environment.fromElements(
                "{\"employee_id\":\"e-1\",\"name\":\"Alice\",\"ts\":\"2026-07-23T00:00:01Z\"}"),
                Map.of("eventTimeField", "ts", "watermarkDelayMs", 0)));
        sources.put("leaders", PipelineDagCompiler.assignWatermarks(environment.fromElements(
                "{\"employee_id\":\"e-1\",\"leader\":\"Bob\",\"ts\":\"2026-07-23T00:00:02Z\"}"),
                Map.of("eventTimeField", "ts", "watermarkDelayMs", 0)));
        Map<String, Object> graph = graph(
                List.of(
                        node("employees", "SOURCE", Map.of()),
                        node("leaders", "SOURCE", Map.of()),
                        node("join", "JOIN", Map.of(
                                "joinType", "INNER", "leftKey", "employee_id",
                                "rightKey", "employee_id", "windowSizeMs", 60_000,
                                "lookupPrefix", "right_"))),
                List.of(edge("employees", "join"), edge("leaders", "join")));

        List<Map<String, Object>> rows = collect(PipelineDagCompiler.compile(
                sources, graph, sourceConfig(), "dag:join", "join"));

        assertEquals(1, rows.size());
        assertEquals("Alice", rows.get(0).get("name"));
        assertEquals("Bob", rows.get(0).get("leader"));
        assertEquals("e-1", rows.get(0).get("right_employee_id"));
    }

    @Test
    void aggregatesByKeyInsideNativeWindow() throws Exception {
        StreamExecutionEnvironment environment = environment();
        DataStream<String> source = PipelineDagCompiler.assignWatermarks(environment.fromElements(
                "{\"team\":\"A\",\"value\":2,\"ts\":\"2026-07-23T00:00:01Z\"}",
                "{\"team\":\"A\",\"value\":3,\"ts\":\"2026-07-23T00:00:02Z\"}"),
                Map.of("eventTimeField", "ts", "watermarkDelayMs", 0));
        Map<String, Object> graph = graph(
                List.of(
                        node("source", "SOURCE", Map.of()),
                        node("window", "WINDOW", Map.of(
                                "groupBy", List.of("team"), "windowSizeMs", 60_000,
                                "windowType", "TUMBLING")),
                        node("aggregate", "AGGREGATE", Map.of(
                                "groupBy", List.of("team"),
                                "aggregations", List.of(
                                        Map.of("operation", "SUM", "field", "value", "outputField", "total"),
                                        Map.of("operation", "COUNT_DISTINCT", "field", "value",
                                                "outputField", "unique_values"))))),
                List.of(edge("source", "window"), edge("window", "aggregate")));

        List<Map<String, Object>> rows = collect(PipelineDagCompiler.compile(
                Map.of("source", source), graph, sourceConfig(), "dag:aggregate", "aggregate"));

        assertEquals(1, rows.size());
        assertEquals("A", rows.get(0).get("team"));
        assertEquals("5", String.valueOf(rows.get(0).get("total")));
        assertEquals(2, rows.get(0).get("unique_values"));
        assertTrue(rows.get(0).containsKey("_window_start"));
        assertTrue(rows.get(0).containsKey("_window_end"));
    }

    @Test
    void deduplicatesWithKeyedState() throws Exception {
        StreamExecutionEnvironment environment = environment();
        DataStream<String> source = environment.fromElements(
                "{\"id\":\"e-1\",\"value\":1}", "{\"id\":\"e-1\",\"value\":2}");
        Map<String, Object> graph = graph(
                List.of(node("source", "SOURCE", Map.of()),
                        node("deduplicate", "DEDUPLICATE", Map.of(
                                "keys", List.of("id"), "stateTtlMs", 60_000))),
                List.of(edge("source", "deduplicate")));

        List<Map<String, Object>> rows = collect(PipelineDagCompiler.compile(
                Map.of("source", source), graph, sourceConfig(), "dag:dedup", "deduplicate"));

        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).get("value"));
    }

    private StreamExecutionEnvironment environment() {
        StreamExecutionEnvironment environment =
                StreamExecutionEnvironment.createLocalEnvironment(1);
        environment.setParallelism(1);
        return environment;
    }

    private List<Map<String, Object>> collect(DataStream<String> stream) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var values = stream.executeAndCollect()) {
            while (values.hasNext()) {
                result.add(json.readValue(values.next(), new TypeReference<>() { }));
            }
        }
        return result;
    }

    private Map<String, Object> sourceConfig() {
        return Map.of(
                "assetId", "asset", "connectionId", "connection", "ontologyId",
                "00000000-0000-0000-0000-00000000a001", "ontologyRevision", 1,
                "pipelineId", "pipeline", "pipelineVersion", 1, "runId", "run");
    }

    private Map<String, Object> graph(List<Map<String, Object>> nodes,
                                      List<Map<String, Object>> edges) {
        return Map.of("edges", edges, "nodes", nodes);
    }

    private Map<String, Object> node(String id, String type, Map<String, Object> config) {
        return Map.of("config", config, "id", id, "inputSchema", List.of(), "type", type);
    }

    private Map<String, Object> edge(String source, String target) {
        return Map.of("id", source + "-" + target, "source", source, "target", target);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
