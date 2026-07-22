package com.hezhangjian.ontology.core.pipelines;

import static com.hezhangjian.ontology.core.pipelines.PipelineModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PipelineGraphValidatorTest {
    private final PipelineGraphValidator validator = new PipelineGraphValidator(new ObjectMapper());
    private final List<FieldSchema> sourceSchema = List.of(
            new FieldSchema("employee_id", "STRING", false, false, "source-1"),
            new FieldSchema("name", "STRING", true, false, "source-1"));

    @Test
    void propagatesSelectedSchemaIntoObjectOutput() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()),
                        node("select-1", "SELECT", Map.of("fields", List.of(
                                Map.of("source", "employee_id", "target", "id"),
                                Map.of("source", "name", "target", "display_name")))),
                        node("output-1", "ONTOLOGY_OBJECT", Map.of(
                                "idField", "id", "mappings", Map.of("displayName", "display_name"),
                                "objectTypeId", "Employee"))),
                List.of(edge("e1", "source-1", "select-1"), edge("e2", "select-1", "output-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.BATCH, sourceSchema);

        assertThat(result.valid()).isTrue();
        PipelineNode output = result.normalizedGraph().nodes().stream()
                .filter(node -> node.id().equals("output-1")).findFirst().orElseThrow();
        assertThat(output.inputSchema()).extracting(FieldSchema::name).containsExactly("id", "display_name");
    }

    @Test
    void rejectsCycles() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()),
                        node("select-1", "SELECT", Map.of("fields", List.of(Map.of("source", "removed")))),
                        node("output-1", "ONTOLOGY_OBJECT", Map.of("idField", "employee_id", "objectTypeId", "Employee"))),
                List.of(edge("e1", "source-1", "select-1"), edge("e2", "select-1", "output-1"),
                        edge("e3", "output-1", "select-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.BATCH, sourceSchema);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(ValidationIssue::id).contains("graph.cycle");
    }

    @Test
    void marksRemovedUpstreamFieldsInvalid() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()),
                        node("select-1", "SELECT", Map.of("fields", List.of(Map.of("source", "removed")))),
                        node("output-1", "ONTOLOGY_OBJECT", Map.of("idField", "employee_id", "objectTypeId", "Employee"))),
                List.of(edge("e1", "source-1", "select-1"), edge("e2", "select-1", "output-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.BATCH, sourceSchema);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(ValidationIssue::id)
                .anyMatch(id -> id.startsWith("select.missing."));
    }

    @Test
    void requiresWindowBeforeStreamingAggregate() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()), node("aggregate-1", "AGGREGATE", Map.of()),
                        node("output-1", "ONTOLOGY_OBJECT", Map.of("idField", "employee_id", "objectTypeId", "Employee"))),
                List.of(edge("e1", "source-1", "aggregate-1"), edge("e2", "aggregate-1", "output-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.STREAMING, sourceSchema);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(ValidationIssue::id).contains("aggregate.window.aggregate-1");
    }

    @Test
    void validatesLookupJoinAndCompoundObjectIdentity() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()),
                        node("join-1", "JOIN", Map.of(
                                "joinType", "LEFT",
                                "leftKey", "employee_id",
                                "lookupAssetId", "8b4a16f2-3fc6-4aca-a32c-7618ce4b2311",
                                "lookupConnectionId", "8b4a16f2-3fc6-4aca-a32c-7618ce4b2312",
                                "lookupFields", List.of(Map.of("name", "leader", "type", "STRING")),
                                "rightKey", "leader")),
                        node("output-1", "ONTOLOGY_OBJECT", Map.of(
                                "idFields", List.of("employee_id", "leader"), "objectTypeId", "Employee"))),
                List.of(edge("e1", "source-1", "join-1"), edge("e2", "join-1", "output-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.BATCH, sourceSchema);

        assertThat(result.valid()).isTrue();
        PipelineNode join = result.normalizedGraph().nodes().stream()
                .filter(node -> node.id().equals("join-1")).findFirst().orElseThrow();
        assertThat(join.outputSchema()).extracting(FieldSchema::name).containsExactly("employee_id", "name", "leader");
    }

    @Test
    void datasetOutputKeepsOnlyExplicitlySelectedFields() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()),
                        node("output-1", "DATASET_OUTPUT", Map.of(
                                "datasetName", "Employee IDs",
                                "fieldMappings", List.of(Map.of("source", "employee_id", "target", "employee_key")),
                                "fieldSelectionMode", "EXPLICIT"))),
                List.of(edge("e1", "source-1", "output-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.BATCH, sourceSchema);

        assertThat(result.valid()).isTrue();
        PipelineNode output = result.normalizedGraph().nodes().stream()
                .filter(node -> node.id().equals("output-1")).findFirst().orElseThrow();
        assertThat(output.outputSchema()).extracting(FieldSchema::name).containsExactly("employee_key");
    }

    @Test
    void datasetOutputRequiresOneExplicitField() {
        PipelineGraph graph = graph(
                List.of(node("source-1", "SOURCE", Map.of()),
                        node("output-1", "DATASET_OUTPUT", Map.of(
                                "datasetName", "Empty",
                                "fieldMappings", List.of(),
                                "fieldSelectionMode", "EXPLICIT"))),
                List.of(edge("e1", "source-1", "output-1")));

        ValidationResult result = validator.validate(graph, PipelineMode.BATCH, sourceSchema);

        assertThat(result.valid()).isFalse();
        assertThat(result.issues()).extracting(ValidationIssue::id).contains("dataset.fields.required.output-1");
    }

    private PipelineGraph graph(List<PipelineNode> nodes, List<PipelineEdge> edges) {
        return new PipelineGraph(nodes, edges);
    }

    private PipelineNode node(String id, String type, Map<String, Object> config) {
        return new PipelineNode(id, type, id, new Position(0, 0), config, List.of(), List.of(), List.of());
    }

    private PipelineEdge edge(String id, String source, String target) {
        return new PipelineEdge(id, source, target);
    }
}
