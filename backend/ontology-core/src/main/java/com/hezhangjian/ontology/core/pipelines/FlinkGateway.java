package com.hezhangjian.ontology.core.pipelines;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class FlinkGateway {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final PipelineProperties properties;
    private volatile String jarId;

    public FlinkGateway(ObjectMapper json, PipelineProperties properties) {
        this.json = json;
        this.properties = properties;
    }

    public String submit(UUID runId, int parallelism) {
        return submit(runId, parallelism, null);
    }

    public String submit(UUID runId, int parallelism, String savepointPath) {
        String uploaded = ensureJar();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("entryClass", "com.hezhangjian.ontology.flink.PipelineJob");
        body.put("parallelism", Math.max(1, Math.min(4, parallelism)));
        body.put("programArgsList", List.of("--core-url", properties.internalCoreUrl(), "--run-id", runId.toString()));
        if (savepointPath != null && !savepointPath.isBlank()) body.put("savepointPath", savepointPath);
        Map<String, Object> response = sendJson("POST", "/jars/" + uploaded + "/run", body);
        Object jobId = response.get("jobid");
        if (jobId == null) throw new IllegalStateException("Flink submission did not return a job ID");
        return String.valueOf(jobId);
    }

    public String submitPreview(UUID previewId, int parallelism) {
        String uploaded = ensureJar();
        Map<String, Object> body = Map.of(
                "entryClass", "com.hezhangjian.ontology.flink.PipelineJob",
                "parallelism", Math.max(1, Math.min(4, parallelism)),
                "programArgsList", List.of("--core-url", properties.internalCoreUrl(),
                        "--preview-id", previewId.toString()));
        Map<String, Object> response = sendJson("POST", "/jars/" + uploaded + "/run", body);
        Object jobId = response.get("jobid");
        if (jobId == null) throw new IllegalStateException("Flink preview submission did not return a job ID");
        return String.valueOf(jobId);
    }

    public String state(String jobId) {
        Map<String, Object> response = sendJson("GET", "/jobs/" + jobId, null);
        return String.valueOf(response.getOrDefault("state", "UNKNOWN"));
    }

    public void cancel(String jobId) {
        sendJson("PATCH", "/jobs/" + jobId + "?mode=cancel", Map.of());
    }

    public String stopWithSavepoint(String jobId, boolean drain) {
        Map<String, Object> response = sendJson("POST", "/jobs/" + jobId + "/stop", Map.of(
                "drain", drain, "formatType", "CANONICAL", "targetDirectory", "s3://flink-checkpoints/savepoints"));
        return String.valueOf(response.getOrDefault("request-id", "pending"));
    }

    public String triggerSavepoint(String jobId) {
        Map<String, Object> response = sendJson("POST", "/jobs/" + jobId + "/savepoints", Map.of(
                "cancel-job", false, "formatType", "CANONICAL", "target-directory", "s3://flink-checkpoints/savepoints"));
        return String.valueOf(response.getOrDefault("request-id", "pending"));
    }

    public Map<String, Object> savepointStatus(String jobId, String requestId) {
        return sendJson("GET", "/jobs/" + jobId + "/savepoints/" + requestId, null);
    }

    public synchronized String ensureJar() {
        if (jarId != null) return jarId;
        try {
            byte[] jar = Files.readAllBytes(properties.jobJar());
            String boundary = "ontology-" + UUID.randomUUID();
            byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"jarfile\"; filename=\"ontology-flink-job.jar\"\r\n"
                    + "Content-Type: application/java-archive\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] body = new byte[prefix.length + jar.length + suffix.length];
            System.arraycopy(prefix, 0, body, 0, prefix.length);
            System.arraycopy(jar, 0, body, prefix.length, jar.length);
            System.arraycopy(suffix, 0, body, prefix.length + jar.length, suffix.length);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.flinkUrl() + "/jars/upload"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Flink JAR upload failed with HTTP "
                    + response.statusCode() + responseDetails(response.body()));
            Map<String, Object> payload = json.readValue(response.body(), new TypeReference<>() { });
            String filename = String.valueOf(payload.get("filename"));
            jarId = filename.substring(filename.lastIndexOf('/') + 1);
            return jarId;
        } catch (IOException cause) {
            throw new IllegalStateException("Cannot read or upload the signed Flink job JAR", cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Flink JAR upload was interrupted", cause);
        }
    }

    private Map<String, Object> sendJson(String method, String path, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.flinkUrl() + path)).timeout(Duration.ofSeconds(30));
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Flink REST " + method + " " + path
                    + " failed with HTTP " + response.statusCode() + responseDetails(response.body()));
            if (response.body() == null || response.body().isBlank()) return Map.of();
            return json.readValue(response.body(), new TypeReference<>() { });
        } catch (JsonProcessingException cause) {
            throw new IllegalStateException("Flink REST returned invalid JSON", cause);
        } catch (IOException cause) {
            throw new IllegalStateException("Flink REST request failed", cause);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Flink REST request interrupted", cause);
        }
    }

    private String responseDetails(String body) {
        if (body == null || body.isBlank()) return "";
        String sanitized = body.replaceAll("[\\r\\n\\t]+", " ").trim();
        return ": " + (sanitized.length() <= 1000 ? sanitized : sanitized.substring(0, 1000) + "…");
    }
}
