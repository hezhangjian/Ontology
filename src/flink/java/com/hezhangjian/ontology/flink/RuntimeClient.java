package com.hezhangjian.ontology.flink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.apache.pulsar.client.api.ClientBuilder;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SizeUnit;

final class RuntimeClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String objectKey;
    private final UUID workloadId;
    private transient RuntimeSpec loaded;
    private transient PulsarClient controlPulsar;
    private transient Producer<byte[]> controlProducer;
    private transient String controlTopic;

    RuntimeClient(String objectKey, UUID workloadId) {
        this.objectKey = objectKey;
        this.workloadId = workloadId;
    }

    RuntimeSpec load() {
        if (loaded != null) return loaded;
        try {
            MinioClient minio = MinioClient.builder().endpoint(environment("MINIO_ENDPOINT", "http://minio:9000"))
                    .credentials(environment("MINIO_ACCESS_KEY", "ontology-minio"),
                            requiredEnvironment("MINIO_SECRET_KEY")).build();
            byte[] envelopeBytes;
            try (var stream = minio.getObject(GetObjectArgs.builder()
                    .bucket(environment("MINIO_BUCKET", "flink-workloads")).object(objectKey).build())) {
                envelopeBytes = stream.readAllBytes();
            }
            Map<String, Object> envelope = JSON.readValue(envelopeBytes, new TypeReference<>() { });
            byte[] nonce = Base64.getDecoder().decode(String.valueOf(envelope.get("nonce")));
            byte[] ciphertext = Base64.getDecoder().decode(String.valueOf(envelope.get("ciphertext")));
            byte[] keyBytes = Base64.getDecoder().decode(requiredEnvironment("FLINK_WORKLOAD_KEY"));
            if (keyBytes.length != 32) throw new IllegalStateException("FLINK_WORKLOAD_KEY must decode to 32 bytes");
            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(objectKey.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(ciphertext);
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext));
            if (!MessageDigest.isEqual(actualHash.getBytes(StandardCharsets.US_ASCII),
                    String.valueOf(envelope.get("sha256")).getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException("Workload bundle hash mismatch");
            }
            Map<String, Object> bundle = JSON.readValue(plaintext, new TypeReference<>() { });
            UUID actualId = UUID.fromString(String.valueOf(bundle.get("workloadId")));
            if (!workloadId.equals(actualId)) throw new IllegalStateException("Workload ID mismatch");
            Instant expiresAt = Instant.parse(String.valueOf(bundle.get("expiresAt")));
            if (!expiresAt.isAfter(Instant.now())) throw new IllegalStateException("Workload bundle has expired");
            loaded = new RuntimeSpec(actualId, String.valueOf(bundle.get("workspaceId")),
                    String.valueOf(bundle.get("kind")), String.valueOf(bundle.get("sourceType")),
                    map(bundle.get("sourceConfig")), strings(bundle.get("credential")),
                    sourceSpecs(bundle.get("sources")), map(bundle.get("graph")), map(bundle.get("runtime")),
                    String.valueOf(bundle.getOrDefault("targetTopic", "")),
                    String.valueOf(bundle.get("controlTopic")),
                    String.valueOf(bundle.get("correlationId")),
                    text(bundle.get("previewNodeId")), integer(bundle.get("previewLimit"), 100));
            publish("WORKLOAD_LOADED", "", 0, 0, 0, "Flink 工作负载已从 MinIO 加载", Map.of(), List.of(),
                    "RUNNING", Map.of());
            return loaded;
        } catch (Exception cause) {
            throw new IllegalStateException("Cannot load encrypted Flink workload bundle", cause);
        }
    }

    void progress(String phase, long read, long written, long rejected, String message) {
        progress(phase, read, written, rejected, message, Map.of());
    }

    void progress(String phase, long read, long written, long rejected, String message,
                  Map<String, Object> safeDetails) {
        publish("PROGRESS", phase, read, written, rejected, message, safeDetails, List.of(), "RUNNING", Map.of());
    }

    void previewCompleted(List<Map<String, Object>> rows, long sizeBytes) {
        publish("PREVIEW_COMPLETED", "", rows.size(), rows.size(), 0, "预览完成",
                Map.of("sizeBytes", sizeBytes), rows, "COMPLETED", Map.of());
    }

    void failed(String message, Map<String, Object> diagnostic) {
        publish("FAILED", "", 0, 0, 0, message, Map.of(), List.of(), "FAILED", diagnostic);
    }

    private void publish(String eventType, String phase, long read, long written, long rejected,
                         String message, Map<String, Object> safeDetails,
                         List<Map<String, Object>> rows, String status,
                         Map<String, Object> diagnostic) {
        RuntimeSpec spec = loaded;
        if (spec == null && !"WORKLOAD_LOADED".equals(eventType)) spec = load();
        String topic = spec == null ? "" : spec.controlTopic();
        if (topic.isBlank()) return;
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("diagnostic", diagnostic);
        event.put("eventId", UUID.randomUUID());
        event.put("eventType", eventType);
        event.put("message", message);
        event.put("occurredAt", Instant.now().toString());
        event.put("phase", phase);
        event.put("readCount", read);
        event.put("rejectedCount", rejected);
        event.put("rows", rows);
        event.put("safeDetails", safeDetails);
        event.put("status", status);
        event.put("workloadId", workloadId);
        event.put("workloadKind", spec.kind());
        event.put("writtenCount", written);
        try {
            ensureControlProducer(topic);
            controlProducer.newMessage().key(workloadId.toString())
                    .value(JSON.writeValueAsBytes(event)).send();
        } catch (Exception cause) {
            throw new IllegalStateException("Cannot publish Flink control event", cause);
        }
    }

    void close() throws Exception {
        if (controlProducer != null) controlProducer.close();
        if (controlPulsar != null) controlPulsar.close();
        controlProducer = null;
        controlPulsar = null;
        controlTopic = null;
    }

    private void ensureControlProducer(String topic) throws Exception {
        if (controlProducer != null && topic.equals(controlTopic)) return;
        close();
        controlPulsar = pulsarClientBuilder(environment("PULSAR_URL", "pulsar://pulsar:6650")).build();
        controlProducer = controlPulsar.newProducer(Schema.BYTES)
                .topic(topic).enableBatching(false).create();
        controlTopic = topic;
    }

    static ClientBuilder pulsarClientBuilder(String serviceUrl) {
        return PulsarClient.builder()
                .serviceUrl(serviceUrl)
                .connectionsPerBroker(1)
                .ioThreads(1)
                .listenerThreads(1)
                .memoryLimit(64, SizeUnit.MEGA_BYTES)
                .statsInterval(0, TimeUnit.SECONDS);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
    }

    private static Map<String, String> strings(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) raw.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return result;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Map<String, SourceSpec> sourceSpecs(Object value) {
        Map<String, SourceSpec> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> raw) raw.forEach((key, item) -> {
            Map<String, Object> source = map(item);
            result.put(String.valueOf(key), new SourceSpec(String.valueOf(source.get("sourceType")),
                    map(source.get("sourceConfig")), strings(source.get("credential"))));
        });
        return result;
    }

    record RuntimeSpec(UUID workloadId, String workspaceId, String kind, String sourceType,
                       Map<String, Object> sourceConfig, Map<String, String> credential,
                       Map<String, SourceSpec> sources, Map<String, Object> graph,
                       Map<String, Object> runtime, String targetTopic, String controlTopic,
                       String correlationId, String previewNodeId, int previewLimit) {
        RuntimeSpec forSource(String nodeId) {
            SourceSpec source = sources.get(nodeId);
            return source == null ? this : new RuntimeSpec(workloadId, workspaceId, kind,
                    source.sourceType(), source.sourceConfig(), source.credential(), sources,
                    graph, runtime, targetTopic, controlTopic, correlationId, previewNodeId, previewLimit);
        }
    }

    record SourceSpec(String sourceType, Map<String, Object> sourceConfig,
                      Map<String, String> credential) { }
}
