package com.hezhangjian.ontology.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

class PipelineTransformTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void previewAndFormalPathShareTypedOperators() throws Exception {
        Map<String, Object> graph = Map.of(
                "edges", List.of(Map.of("id", "e1", "source", "source-1", "target", "select-1")),
                "nodes", List.of(
                        Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of("fields", List.of(
                                Map.of("source", "id", "target", "employee_id"),
                                Map.of("source", "age", "target", "age"))),
                                "id", "select-1", "type", "SELECT")));
        PipelineTransform transform = new PipelineTransform(graph, Map.of(), "preview:test", "select-1");
        transform.open(new Configuration());
        List<String> values = new ArrayList<>();

        transform.flatMap("{\"id\":\"e-1\",\"age\":35,\"ignored\":true}", collector(values));

        Map<String, Object> row = json.readValue(values.get(0), new TypeReference<>() { });
        assertEquals(Map.of("age", 35, "employee_id", "e-1"), row);
    }

    @Test
    void joinsLookupRowsAndBuildsStableCompoundIdentity() throws Exception {
        Map<String, Object> graph = Map.of(
                "edges", List.of(
                        Map.of("id", "e1", "source", "source-1", "target", "join-1"),
                        Map.of("id", "e2", "source", "join-1", "target", "output-1")),
                "nodes", List.of(
                        Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of(
                                "joinType", "LEFT", "leftKey", "employee_id", "rightKey", "employee_id",
                                "lookupRows", List.of(Map.of("employee_id", "e-1", "leader", "Alice"))),
                                "id", "join-1", "type", "JOIN"),
                        Map.of("config", Map.of(
                                "idFields", List.of("month", "employee_id"),
                                "mappings", Map.of("leader", "leader"),
                                "objectTypeId", "usage",
                                "primaryPropertyKey", "record_key"),
                                "id", "output-1", "inputSchema", List.of(), "type", "ONTOLOGY_OBJECT")));
        PipelineTransform transform = new PipelineTransform(graph,
                Map.of("assetId", "asset", "connectionId", "connection", "ontologyRevision", 1,
                        "pipelineId", "pipeline", "pipelineVersion", 1, "runId", "run"), "test");
        transform.open(new Configuration());
        List<String> values = new ArrayList<>();

        transform.flatMap("{\"employee_id\":\"e-1\",\"month\":\"2026-07\"}", collector(values));

        Map<String, Object> event = json.readValue(values.get(0), new TypeReference<>() { });
        Map<?, ?> payload = (Map<?, ?>) event.get("payload");
        assertEquals("Alice", payload.get("leader"));
        assertEquals(64, String.valueOf(event.get("object_id")).length());
        assertEquals(event.get("object_id"), payload.get("record_key"));
    }

    @Test
    void keepsEventIdsStableWithinARunAndDistinctAcrossRetries() throws Exception {
        Map<String, Object> graph = Map.of(
                "edges", List.of(Map.of("id", "e1", "source", "source-1", "target", "output-1")),
                "nodes", List.of(
                        Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of(
                                "idField", "employee_id",
                                "mappings", Map.of("name", "name"),
                                "objectTypeId", "employee"),
                                "id", "output-1", "inputSchema", List.of(), "type", "ONTOLOGY_OBJECT")));
        Map<String, Object> source = Map.of(
                "assetId", "asset", "connectionId", "connection", "ontologyRevision", 1,
                "pipelineId", "pipeline", "pipelineVersion", 1, "runId", "run");

        Map<String, Object> first = event(graph, source, "pipeline:run:first");
        Map<String, Object> repeated = event(graph, source, "pipeline:run:first");
        Map<String, Object> retry = event(graph, source, "pipeline:run:retry");

        assertEquals(first.get("event_id"), repeated.get("event_id"));
        assertEquals(first.get("object_id"), retry.get("object_id"));
        assertNotEquals(first.get("event_id"), retry.get("event_id"));
    }

    @Test
    void emitsMappedDatasetRowsAndPreservesDuplicateRows() throws Exception {
        Map<String, Object> graph = Map.of(
                "edges", List.of(Map.of("id", "e1", "source", "source-1", "target", "output-1")),
                "nodes", List.of(
                        Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of(
                                "datasetId", "dataset-1",
                                "datasetName", "Usage",
                                "fieldMappings", List.of(
                                        Map.of("source", "tokens", "target", "total_tokens"),
                                        Map.of("source", "ignored", "target", "")),
                                "fieldSelectionMode", "EXPLICIT"),
                                "id", "output-1", "inputSchema", List.of(), "type", "DATASET_OUTPUT")));
        PipelineTransform transform = new PipelineTransform(graph, Map.of(
                "assetId", "asset", "connectionId", "connection", "ontologyRevision", 1,
                "pipelineId", "pipeline", "pipelineVersion", 1, "runId", "run"), "dataset:test");
        transform.open(new Configuration());
        List<String> values = new ArrayList<>();

        transform.flatMap("{\"tokens\":42,\"ignored\":true}", collector(values));
        transform.flatMap("{\"tokens\":42,\"ignored\":true}", collector(values));

        Map<String, Object> first = json.readValue(values.get(0), new TypeReference<>() { });
        Map<String, Object> second = json.readValue(values.get(1), new TypeReference<>() { });
        assertEquals("dataset.row", first.get("event_type"));
        assertEquals(Map.of("total_tokens", 42), first.get("payload"));
        assertNotEquals(first.get("event_id"), second.get("event_id"));
    }

    @Test
    void replacesOutOfRangeValuesAndPreservesRawEvidence() throws Exception {
        Map<String, Object> graph = Map.of(
                "edges", List.of(Map.of("id", "e1", "source", "source-1", "target", "clean-1")),
                "nodes", List.of(
                        Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of("rules", List.of(Map.of(
                                        "action", "REPLACE",
                                        "field", "reading",
                                        "max", 9999,
                                        "min", 1,
                                        "operator", "OUTSIDE_RANGE",
                                        "preserveOriginalAs", "raw_reading",
                                        "replacement", 0,
                                        "statusField", "cleaning_status"))),
                                "id", "clean-1", "type", "RULE_TRANSFORM")));
        PipelineTransform transform = new PipelineTransform(graph, Map.of(), "preview:clean", "clean-1");
        transform.open(new Configuration());
        List<String> values = new ArrayList<>();

        transform.flatMap("{\"reading\":27048}", collector(values));

        Map<String, Object> row = json.readValue(values.get(0), new TypeReference<>() { });
        assertEquals(0, row.get("reading"));
        assertEquals(27048, row.get("raw_reading"));
        assertEquals("CLEANED", row.get("cleaning_status"));
        assertTrue(row.containsKey("raw_reading"));
    }

    @Test
    void leavesBlankNumericReadingsUntouched() throws Exception {
        Map<String, Object> graph = Map.of(
                "edges", List.of(Map.of("id", "e1", "source", "source-1", "target", "clean-1")),
                "nodes", List.of(
                        Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of("rules", List.of(Map.of(
                                        "action", "REPLACE", "field", "reading", "max", 9999, "min", 1,
                                        "operator", "OUTSIDE_RANGE", "replacement", 0))),
                                "id", "clean-1", "type", "RULE_TRANSFORM")));
        PipelineTransform transform = new PipelineTransform(graph, Map.of(), "preview:blank", "clean-1");
        transform.open(new Configuration());
        List<String> values = new ArrayList<>();

        transform.flatMap("{\"reading\":\"\"}", collector(values));

        Map<String, Object> row = json.readValue(values.get(0), new TypeReference<>() { });
        assertEquals("", row.get("reading"));
    }

    private Map<String, Object> event(Map<String, Object> graph, Map<String, Object> source,
                                      String correlationId) throws Exception {
        PipelineTransform transform = new PipelineTransform(graph, source, correlationId);
        transform.open(new Configuration());
        List<String> values = new ArrayList<>();
        transform.flatMap("{\"employee_id\":\"e-1\",\"name\":\"Alice\"}", collector(values));
        return json.readValue(values.get(0), new TypeReference<>() { });
    }

    private Collector<String> collector(List<String> values) {
        return new Collector<>() {
            @Override public void collect(String record) { values.add(record); }
            @Override public void close() { }
        };
    }
}
