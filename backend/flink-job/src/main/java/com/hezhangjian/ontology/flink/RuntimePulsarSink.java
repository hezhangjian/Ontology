package com.hezhangjian.ontology.flink;

import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    private transient PulsarClient pulsar;
    private long written;

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
        client.progress("PUBLISHING", 0, 0, 0, "开始向平台 Pulsar 发布本体事件");
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        Map<String, Object> event = json.readValue(value, new TypeReference<>() { });
        boolean relation = String.valueOf(event.get("event_type")).startsWith("relation.");
        String key = relation
                ? event.get("relation_type") + ":" + event.get("relation_id")
                : event.get("object_type") + ":" + event.get("object_id");
        Producer<byte[]> producer = relation ? relationProducer : objectProducer;
        producer.newMessage().key(key).value(value.getBytes(StandardCharsets.UTF_8)).send();
        written++;
        client.progress("PUBLISHING", written, written, 0, "已发布本体事件到平台 Pulsar");
    }

    @Override
    public void close() throws Exception {
        if (objectProducer != null) objectProducer.close();
        if (relationProducer != null) relationProducer.close();
        if (pulsar != null) pulsar.close();
    }
}
