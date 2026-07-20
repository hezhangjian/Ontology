package com.hezhangjian.ontology.flink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;

final class RuntimePreviewSink extends RichSinkFunction<String> {
    private final String coreUrl;
    private final int limit;
    private final UUID previewId;
    private transient RuntimeClient client;
    private transient ObjectMapper json;
    private transient List<Map<String, Object>> rows;
    private long sizeBytes;

    RuntimePreviewSink(String coreUrl, UUID previewId, int limit) {
        this.coreUrl = coreUrl;
        this.previewId = previewId;
        this.limit = Math.max(1, Math.min(100, limit));
    }

    @Override
    public void open(Configuration parameters) {
        client = new RuntimeClient(coreUrl, previewId);
        json = new ObjectMapper();
        rows = new ArrayList<>();
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        long candidateBytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (rows.size() >= limit || sizeBytes + candidateBytes > 1_048_576) return;
        rows.add(json.readValue(value, new TypeReference<>() { }));
        sizeBytes += candidateBytes;
    }

    @Override
    public void close() {
        if (client != null && rows != null) client.completePreview(previewId, rows, sizeBytes);
    }
}
