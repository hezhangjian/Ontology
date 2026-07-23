package com.hezhangjian.ontology.flink;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

final class RuntimeClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String coreUrl;
    private final UUID runId;

    RuntimeClient(String coreUrl, UUID runId) {
        this.coreUrl = coreUrl;
        this.runId = runId;
    }

    RuntimeSpec exchange() {
        Map<String, Object> response = request("/internal/v1/workload-credentials/exchange",
                Map.of("jobSignature", jobSignature(), "runId", runId));
        return new RuntimeSpec(
                String.valueOf(response.get("sourceType")), map(response.get("sourceConfig")), strings(response.get("credential")),
                map(response.get("graph")), map(response.get("runtime")), String.valueOf(response.get("targetTopic")),
                String.valueOf(response.get("correlationId")));
    }

    PreviewSpec exchangePreview(UUID previewId) {
        Map<String, Object> response = request("/internal/v1/workload-credentials/preview-exchange",
                Map.of("jobSignature", jobSignature(), "previewId", previewId));
        RuntimeSpec runtime = new RuntimeSpec(
                String.valueOf(response.get("sourceType")), map(response.get("sourceConfig")), strings(response.get("credential")),
                map(response.get("graph")), map(response.get("runtime")), "", String.valueOf(response.get("correlationId")));
        return new PreviewSpec(runtime, String.valueOf(response.get("nodeId")), integer(response.get("limit"), 100));
    }

    void completePreview(UUID previewId, java.util.List<Map<String, Object>> rows, long sizeBytes) {
        request("/internal/v1/pipeline-previews/" + previewId + "/result", Map.of(
                "diagnostic", Map.of(), "rows", rows, "sizeBytes", sizeBytes, "status", "COMPLETED"));
    }

    void progress(String phase, long read, long written, long rejected, String message) {
        progress(phase, read, written, rejected, message, Map.of());
    }

    void progress(String phase, long read, long written, long rejected, String message,
                  Map<String, Object> safeDetails) {
        request("/internal/v1/pipeline-runs/" + runId + "/progress", Map.of(
                "message", message, "phase", phase, "readCount", read,
                "rejectedCount", rejected, "safeDetails", safeDetails, "writtenCount", written));
    }

    private Map<String, Object> request(String path, Map<String, Object> body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(coreUrl + path))
                    .header("Content-Type", "application/json")
                    .header("X-Workload-Token", workloadToken())
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Ontology Core runtime endpoint returned HTTP "
                    + response.statusCode() + responseDetails(response.body()));
            if (response.body().isBlank()) return Map.of();
            return JSON.readValue(response.body(), new TypeReference<>() { });
        } catch (IOException cause) {
            throw new IllegalStateException("Ontology Core runtime endpoint is unavailable", cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ontology Core runtime request was interrupted", cause);
        }
    }

    private String responseDetails(String body) {
        if (body == null || body.isBlank()) return "";
        String sanitized = body.replaceAll("[\\r\\n\\t]+", " ").trim();
        return ": " + (sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000) + "…");
    }

    private String workloadToken() {
        String token = System.getenv("FLINK_WORKLOAD_TOKEN");
        if (token != null && !token.isBlank()) return token.trim();
        try { return Files.readString(Path.of("/run/secrets/flink_workload_token"), StandardCharsets.UTF_8).trim(); }
        catch (IOException cause) { throw new IllegalStateException("Flink workload identity is unavailable", cause); }
    }

    private String jobSignature() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes("ontology-flink-job:v1")));
        } catch (JsonProcessingException | NoSuchAlgorithmException cause) {
            throw new IllegalStateException("Cannot calculate signed job identity", cause);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? new LinkedHashMap<>((Map<String, Object>) raw) : Map.of();
    }

    private Map<String, String> strings(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) raw.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    record RuntimeSpec(String sourceType, Map<String, Object> sourceConfig, Map<String, String> credential,
                       Map<String, Object> graph, Map<String, Object> runtime, String targetTopic,
                       String correlationId) { }

    record PreviewSpec(RuntimeSpec runtime, String nodeId, int limit) { }
}
