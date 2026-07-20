package com.hezhangjian.ontology.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private Collector<String> collector(List<String> values) {
        return new Collector<>() {
            @Override public void collect(String record) { values.add(record); }
            @Override public void close() { }
        };
    }
}
