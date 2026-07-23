package com.hezhangjian.ontology.flink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.util.Collector;
import org.junit.jupiter.api.Test;

class OeeScenarioRuleTransformTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cleansEveryInvalidLineOneMounterReadingInTheLastCompleteWeek() throws Exception {
        Path csv = scenarioFile("03_equipment_hourly_metrics.csv");
        List<String> lines = Files.readAllLines(csv);
        String[] header = lines.get(0).split(",", -1);
        PipelineTransform transform = new PipelineTransform(graph(), Map.of(), "scenario:clean", "clean-1");
        transform.open(new Configuration());
        List<String> output = new ArrayList<>();
        int matched = 0;
        for (String line : lines.subList(1, lines.size())) {
            String[] values = line.split(",", -1);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 0; index < header.length; index++) row.put(header[index], values[index]);
            if (!"PL-SMT-01".equals(row.get("line_id"))
                    || !"EQ-SMT-L01-MNT01".equals(row.get("equipment_id"))
                    || String.valueOf(row.get("report_date")).compareTo("2026-07-15") < 0
                    || String.valueOf(row.get("report_date")).compareTo("2026-07-21") > 0) continue;
            int reading = Integer.parseInt(String.valueOf(row.get("pcb_barcode_reading")));
            if (reading < 1 || reading > 9999) matched++;
            transform.flatMap(json.writeValueAsString(row), collector(output));
        }
        long cleaned = output.stream().map(this::read).filter(row -> Integer.valueOf(0).equals(row.get("pcb_barcode_reading")))
                .filter(row -> "CLEANED".equals(row.get("cleaning_status"))).count();
        assertEquals(56, matched);
        assertEquals(56, cleaned);
        assertEquals(168, output.size());
    }

    @Test
    void fixtureSupportsTheExpectedGroundedDiagnosis() throws Exception {
        List<Map<String, String>> alarms = csvRows("05_equipment_alarms.csv").stream()
                .filter(row -> "PL-SMT-01".equals(row.get("line_id")))
                .filter(row -> row.get("alarm_started_at").compareTo("2026-07-15") >= 0)
                .filter(row -> row.get("alarm_started_at").compareTo("2026-07-22") < 0)
                .toList();
        Map<String, Integer> alarmCounts = new LinkedHashMap<>();
        alarms.forEach(row -> alarmCounts.merge(row.get("equipment_id"), 1, Integer::sum));
        Map.Entry<String, Integer> worst = alarmCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();
        assertEquals("EQ-SMT-L01-MNT01", worst.getKey());
        assertEquals(56, worst.getValue());

        List<Map<String, String>> rootCause = alarms.stream()
                .filter(row -> worst.getKey().equals(row.get("equipment_id"))).toList();
        assertTrue(rootCause.stream().allMatch(row -> "PCB_BARCODE_OUT_OF_RANGE".equals(row.get("alarm_code"))));
        assertTrue(rootCause.stream().allMatch(row -> "1".equals(row.get("normal_min"))));
        assertTrue(rootCause.stream().allMatch(row -> "9999".equals(row.get("normal_max"))));
        assertTrue(rootCause.stream().allMatch(row -> "true".equals(row.get("requires_manual_intervention"))));
        assertTrue(rootCause.stream().allMatch(row -> row.get("recovery_action").contains("人工")));
    }

    private Map<String, Object> graph() {
        return Map.of("edges", List.of(Map.of("id", "e1", "source", "source-1", "target", "clean-1")),
                "nodes", List.of(Map.of("config", Map.of(), "id", "source-1", "type", "SOURCE"),
                        Map.of("config", Map.of("rules", List.of(Map.of(
                                "action", "REPLACE", "field", "pcb_barcode_reading", "max", 9999, "min", 1,
                                "operator", "OUTSIDE_RANGE", "preserveOriginalAs", "raw_pcb_barcode_reading",
                                "replacement", 0, "statusField", "cleaning_status"))),
                                "id", "clean-1", "type", "RULE_TRANSFORM")));
    }

    private Path scenarioFile(String name) {
        for (Path candidate : List.of(Path.of("examples/demo-data/oee-line1-scenario", name),
                Path.of("../../examples/demo-data/oee-line1-scenario", name))) {
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("OEE scenario fixture not found");
    }

    private List<Map<String, String>> csvRows(String name) throws Exception {
        List<String> lines = Files.readAllLines(scenarioFile(name));
        String[] header = lines.get(0).split(",", -1);
        return lines.subList(1, lines.size()).stream().map(line -> {
            String[] values = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int index = 0; index < header.length; index++) row.put(header[index], values[index]);
            return row;
        }).toList();
    }

    private Map<String, Object> read(String value) {
        try { return json.readValue(value, new TypeReference<>() { }); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private Collector<String> collector(List<String> values) {
        return new Collector<>() {
            @Override public void collect(String record) { values.add(record); }
            @Override public void close() { }
        };
    }
}
