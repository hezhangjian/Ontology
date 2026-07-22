package com.hezhangjian.ontology.flink;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;

final class RuntimePulsarSink extends RichSinkFunction<String> {
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
    private transient Set<String> emittedEventIds;
    private String datasetId;
    private String correlationId;

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
        emittedEventIds = new HashSet<>();
        client.progress("PUBLISHING", 0, 0, 0, "开始向平台 Pulsar 发布管道结果");
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        Map<String, Object> event = json.readValue(value, new TypeReference<>() { });
        if (!emittedEventIds.add(String.valueOf(event.get("event_id")))) return;
        boolean dataset = "dataset.row".equals(String.valueOf(event.get("event_type")));
        boolean relation = String.valueOf(event.get("event_type")).startsWith("relation.");
        String key = dataset ? String.valueOf(event.get("dataset_id")) : relation
                ? event.get("relation_type") + ":" + event.get("relation_id")
                : event.get("object_type") + ":" + event.get("object_id");
        Producer<byte[]> producer = dataset ? datasetProducer : relation ? relationProducer : objectProducer;
        producer.newMessage().key(key).value(value.getBytes(StandardCharsets.UTF_8)).send();
        if (dataset) {
            datasetId = String.valueOf(event.get("dataset_id"));
            correlationId = String.valueOf(event.get("correlation_id"));
        }
        written++;
        if (written % 100 == 0) {
            client.progress("PUBLISHING", written, written, 0, "正在向平台 Pulsar 发布本体事件");
        }
    }

    @Override
    public void close() throws Exception {
        if (datasetProducer != null && datasetId != null) {
            Map<String, Object> completed = Map.of(
                    "correlation_id", correlationId,
                    "dataset_id", datasetId,
                    "event_type", "dataset.complete",
                    "row_count", written,
                    "run_id", runId.toString());
            datasetProducer.newMessage().key(datasetId)
                    .value(json.writeValueAsBytes(completed)).send();
        }
        if (client != null && written % 100 != 0) {
            client.progress("PUBLISHING", written, written, 0, "管道结果已发布到平台 Pulsar");
        }
        if (datasetProducer != null) datasetProducer.close();
        if (objectProducer != null) objectProducer.close();
        if (relationProducer != null) relationProducer.close();
        if (pulsar != null) pulsar.close();
    }
}
