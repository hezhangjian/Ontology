package com.hezhangjian.ontology.flink;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.legacy.RichSinkFunction;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;

final class RuntimePulsarSink extends RichSinkFunction<String> implements CheckpointedFunction {
    private final String objectKey;
    private final UUID runId;
    private final String targetPrefix;
    private final Map<String, Object> graph;
    private final Map<String, Object> source;
    private final String correlationId;
    private transient RuntimeClient client;
    private transient ObjectMapper json;
    private transient Producer<byte[]> objectCommandProducer;
    private transient Producer<byte[]> relationProducer;
    private transient Producer<byte[]> datasetProducer;
    private transient PulsarClient pulsar;
    private long written;
    private long datasetWritten;
    private long objectWritten;
    private long projectionWritten;
    private transient Map<String, Long> datasetCounts;
    private transient Map<String, String> datasetCorrelations;
    private transient Map<String, Long> objectCounts;
    private transient Map<String, Map<String, Object>> objectBindings;
    private transient ListState<String> progressState;

    RuntimePulsarSink(
            String objectKey,
            UUID runId,
            String targetPrefix,
            Map<String, Object> graph,
            Map<String, Object> source,
            String correlationId) {
        this.objectKey = objectKey;
        this.runId = runId;
        this.targetPrefix = targetPrefix;
        this.graph = graph;
        this.source = source;
        this.correlationId = correlationId;
    }

    @Override
    public void open(OpenContext context) throws Exception {
        client = new RuntimeClient(objectKey, runId);
        json = new ObjectMapper();
        String pulsarUrl = System.getenv("PULSAR_URL");
        pulsar = RuntimeClient.pulsarClientBuilder(
                pulsarUrl == null || pulsarUrl.isBlank() ? "pulsar://pulsar:6650" : pulsarUrl.trim()).build();
        objectCommandProducer = pulsar.newProducer(Schema.BYTES)
                .topic(targetPrefix + "/pipeline-object-commands").create();
        relationProducer = pulsar.newProducer(Schema.BYTES).topic(targetPrefix + "/relation-events").create();
        datasetProducer = pulsar.newProducer(Schema.BYTES).topic(targetPrefix + "/dataset-events").create();
        if (datasetCounts == null) datasetCounts = new LinkedHashMap<>();
        if (datasetCorrelations == null) datasetCorrelations = datasetCorrelations();
        if (objectCounts == null) objectCounts = new LinkedHashMap<>();
        if (objectBindings == null) objectBindings = objectBindings();
        client.progress("PUBLISHING", 0, 0, 0, "开始向平台 Pulsar 发布管道结果");
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        Map<String, Object> event = json.readValue(value, new TypeReference<>() { });
        boolean dataset = "dataset.row".equals(String.valueOf(event.get("event_type")));
        boolean object = "pipeline.object.row".equals(String.valueOf(event.get("event_type")));
        boolean relation = String.valueOf(event.get("event_type")).startsWith("relation.");
        String key = dataset ? String.valueOf(event.get("dataset_id")) : object
                ? String.valueOf(event.get("output_node_id")) : relation
                ? event.get("relation_type") + ":" + event.get("relation_id")
                : event.get("object_type") + ":" + event.get("object_id");
        if (!dataset && !object && !relation) {
            throw new IllegalStateException("Unsupported Pipeline output event");
        }
        Producer<byte[]> producer = dataset
                ? datasetProducer
                : object ? objectCommandProducer : relationProducer;
        producer.newMessage().key(key).value(value.getBytes(StandardCharsets.UTF_8)).sendAsync();
        if (dataset) {
            String datasetId = String.valueOf(event.get("dataset_id"));
            datasetCounts.merge(datasetId, 1L, Long::sum);
            datasetCorrelations.put(datasetId, String.valueOf(event.get("correlation_id")));
            datasetWritten++;
        } else if (object) {
            String outputNodeId = String.valueOf(event.get("output_node_id"));
            objectCounts.merge(outputNodeId, 1L, Long::sum);
            objectWritten++;
        } else {
            projectionWritten++;
        }
        written++;
        if (written % 500 == 0) {
            producer.flush();
            reportProgress("正在向平台 Pulsar 发布管道结果");
        }
    }

    @Override
    public void finish() throws Exception {
        flushProducers();
        if (datasetProducer != null) {
            for (Map.Entry<String, String> dataset : datasetCorrelations.entrySet()) {
                Map<String, Object> completed = Map.of(
                        "correlation_id", dataset.getValue(),
                        "dataset_id", dataset.getKey(),
                        "event_type", "dataset.complete",
                        "row_count", datasetCounts.getOrDefault(dataset.getKey(), 0L),
                        "run_id", runId.toString());
                datasetProducer.newMessage().key(dataset.getKey())
                        .value(json.writeValueAsBytes(completed)).send();
            }
        }
        if (objectCommandProducer != null) {
            for (Map.Entry<String, Map<String, Object>> binding : objectBindings.entrySet()) {
                Map<String, Object> completed = new LinkedHashMap<>(binding.getValue());
                completed.put("correlation_id", correlationId);
                completed.put("event_type", "pipeline.object.complete");
                completed.put("row_count", objectCounts.getOrDefault(binding.getKey(), 0L));
                completed.put("run_id", runId.toString());
                objectCommandProducer.newMessage().key(binding.getKey())
                        .value(json.writeValueAsBytes(completed)).send();
            }
        }
        if (client != null && written % 500 != 0) {
            reportProgress("管道结果已发布到平台 Pulsar");
        }
    }

    @Override
    public void close() throws Exception {
        flushProducers();
        if (datasetProducer != null) datasetProducer.close();
        if (objectCommandProducer != null) objectCommandProducer.close();
        if (relationProducer != null) relationProducer.close();
        if (pulsar != null) pulsar.close();
        if (client != null) client.close();
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        progressState.clear();
        progressState.add(new ObjectMapper().writeValueAsString(Map.of(
                "datasetCorrelations", datasetCorrelations,
                "datasetCounts", datasetCounts,
                "datasetWritten", datasetWritten,
                "objectCounts", objectCounts,
                "objectWritten", objectWritten,
                "projectionWritten", projectionWritten,
                "written", written)));
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        progressState = context.getOperatorStateStore()
                .getListState(new ListStateDescriptor<>("pulsar-sink-progress", String.class));
        datasetCounts = new LinkedHashMap<>();
        datasetCorrelations = datasetCorrelations();
        objectCounts = new LinkedHashMap<>();
        objectBindings = objectBindings();
        if (context.isRestored()) {
            ObjectMapper mapper = new ObjectMapper();
            for (String encoded : progressState.get()) {
                Map<String, Object> state = mapper.readValue(encoded, new TypeReference<>() { });
                mapLongs(state.get("datasetCounts")).forEach(
                        (datasetId, count) -> datasetCounts.merge(datasetId, count, Long::sum));
                mapStrings(state.get("datasetCorrelations")).forEach(datasetCorrelations::put);
                datasetWritten = Math.max(datasetWritten, number(state.get("datasetWritten")));
                mapLongs(state.get("objectCounts")).forEach(
                        (nodeId, count) -> objectCounts.merge(nodeId, count, Long::sum));
                objectWritten = Math.max(objectWritten, number(state.get("objectWritten")));
                projectionWritten = Math.max(projectionWritten, number(state.get("projectionWritten")));
                written = Math.max(written, number(state.get("written")));
            }
        }
    }

    private Map<String, Long> mapLongs(Object value) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) values.forEach((key, count) ->
                result.put(String.valueOf(key), number(count)));
        return result;
    }

    private Map<String, String> mapStrings(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> values) values.forEach((key, item) ->
                result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private void reportProgress(String message) {
        client.progress("PUBLISHING", written, written, 0, message,
                Map.of(
                        "objectWrittenCount", objectWritten,
                        "projectionWrittenCount", projectionWritten));
    }

    private void flushProducers() throws Exception {
        if (datasetProducer != null) datasetProducer.flush();
        if (objectCommandProducer != null) objectCommandProducer.flush();
        if (relationProducer != null) relationProducer.flush();
    }

    private Map<String, Map<String, Object>> objectBindings() {
        Map<String, Map<String, Object>> bindings = new LinkedHashMap<>();
        Object nodes = graph == null ? null : graph.get("nodes");
        if (!(nodes instanceof Iterable<?> values)) {
            return bindings;
        }
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> node)
                    || !"OBJECT_OUTPUT".equals(String.valueOf(node.get("type")))) {
                continue;
            }
            String nodeId = String.valueOf(node.get("id"));
            Map<?, ?> config = node.get("config") instanceof Map<?, ?> map ? map : Map.of();
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("object_type", String.valueOf(config.get("objectTypeId")));
            binding.put("ontology_id", source.get("ontologyId"));
            binding.put("output_node_id", nodeId);
            binding.put("pipeline_id", source.get("pipelineId"));
            binding.put("pipeline_mode", source.get("pipelineMode"));
            binding.put("pipeline_version", source.get("pipelineVersion"));
            bindings.put(nodeId, binding);
        }
        return bindings;
    }

    private Map<String, String> datasetCorrelations() {
        Map<String, String> correlations = new LinkedHashMap<>();
        Object nodes = graph == null ? null : graph.get("nodes");
        if (!(nodes instanceof Iterable<?> values)) {
            return correlations;
        }
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> node)
                    || !"DATASET_OUTPUT".equals(String.valueOf(node.get("type")))) {
                continue;
            }
            Map<?, ?> config = node.get("config") instanceof Map<?, ?> map ? map : Map.of();
            String datasetId = String.valueOf(config.get("datasetId"));
            if (!datasetId.isBlank() && !"null".equals(datasetId)) {
                correlations.put(datasetId, correlationId);
            }
        }
        return correlations;
    }
}
