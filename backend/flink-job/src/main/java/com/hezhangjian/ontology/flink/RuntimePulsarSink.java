package com.hezhangjian.ontology.flink;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;

final class RuntimePulsarSink extends RichSinkFunction<String> implements CheckpointedFunction {
    private final String coreUrl;
    private final UUID runId;
    private final String targetPrefix;
    private transient RuntimeClient client;
    private transient ObjectMapper json;
    private transient Producer<byte[]> objectProducer;
    private transient Producer<byte[]> relationProducer;
    private transient Producer<byte[]> datasetProducer;
    private transient PulsarClient pulsar;
    private long written;
    private long datasetWritten;
    private long projectionWritten;
    private transient Map<String, Long> datasetCounts;
    private transient Map<String, String> datasetCorrelations;
    private transient ListState<String> progressState;

    RuntimePulsarSink(String coreUrl, UUID runId, String targetPrefix) {
        this.coreUrl = coreUrl;
        this.runId = runId;
        this.targetPrefix = targetPrefix;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        client = new RuntimeClient(coreUrl, runId);
        json = new ObjectMapper();
        pulsar = PulsarClient.builder().serviceUrl("pulsar://pulsar:6650").build();
        objectProducer = pulsar.newProducer(Schema.BYTES).topic(targetPrefix + "/object-events").create();
        relationProducer = pulsar.newProducer(Schema.BYTES).topic(targetPrefix + "/relation-events").create();
        datasetProducer = pulsar.newProducer(Schema.BYTES).topic(targetPrefix + "/dataset-events").create();
        if (datasetCounts == null) datasetCounts = new LinkedHashMap<>();
        if (datasetCorrelations == null) datasetCorrelations = new LinkedHashMap<>();
        client.progress("PUBLISHING", 0, 0, 0, "开始向平台 Pulsar 发布管道结果");
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        Map<String, Object> event = json.readValue(value, new TypeReference<>() { });
        boolean dataset = "dataset.row".equals(String.valueOf(event.get("event_type")));
        boolean relation = String.valueOf(event.get("event_type")).startsWith("relation.");
        String key = dataset ? String.valueOf(event.get("dataset_id")) : relation
                ? event.get("relation_type") + ":" + event.get("relation_id")
                : event.get("object_type") + ":" + event.get("object_id");
        Producer<byte[]> producer = dataset ? datasetProducer : relation ? relationProducer : objectProducer;
        producer.newMessage().key(key).value(value.getBytes(StandardCharsets.UTF_8)).send();
        if (dataset) {
            String datasetId = String.valueOf(event.get("dataset_id"));
            datasetCounts.merge(datasetId, 1L, Long::sum);
            datasetCorrelations.put(datasetId, String.valueOf(event.get("correlation_id")));
            datasetWritten++;
        } else {
            projectionWritten++;
        }
        written++;
        if (written % 100 == 0) {
            reportProgress("正在向平台 Pulsar 发布管道结果");
        }
    }

    @Override
    public void close() throws Exception {
        if (datasetProducer != null) {
            for (Map.Entry<String, Long> dataset : datasetCounts.entrySet()) {
                Map<String, Object> completed = Map.of(
                        "correlation_id", datasetCorrelations.get(dataset.getKey()),
                        "dataset_id", dataset.getKey(),
                        "event_type", "dataset.complete",
                        "row_count", dataset.getValue(),
                        "run_id", runId.toString());
                datasetProducer.newMessage().key(dataset.getKey())
                        .value(json.writeValueAsBytes(completed)).send();
            }
        }
        if (client != null && written % 100 != 0) {
            reportProgress("管道结果已发布到平台 Pulsar");
        }
        if (datasetProducer != null) datasetProducer.close();
        if (objectProducer != null) objectProducer.close();
        if (relationProducer != null) relationProducer.close();
        if (pulsar != null) pulsar.close();
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        progressState.clear();
        progressState.add(new ObjectMapper().writeValueAsString(Map.of(
                "datasetCorrelations", datasetCorrelations,
                "datasetCounts", datasetCounts,
                "datasetWritten", datasetWritten,
                "projectionWritten", projectionWritten,
                "written", written)));
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        progressState = context.getOperatorStateStore()
                .getListState(new ListStateDescriptor<>("pulsar-sink-progress", String.class));
        datasetCounts = new LinkedHashMap<>();
        datasetCorrelations = new LinkedHashMap<>();
        if (context.isRestored()) {
            ObjectMapper mapper = new ObjectMapper();
            for (String encoded : progressState.get()) {
                Map<String, Object> state = mapper.readValue(encoded, new TypeReference<>() { });
                mapLongs(state.get("datasetCounts")).forEach(
                        (datasetId, count) -> datasetCounts.merge(datasetId, count, Long::sum));
                mapStrings(state.get("datasetCorrelations")).forEach(datasetCorrelations::put);
                datasetWritten = Math.max(datasetWritten, number(state.get("datasetWritten")));
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
                Map.of("projectionWrittenCount", projectionWritten));
    }
}
