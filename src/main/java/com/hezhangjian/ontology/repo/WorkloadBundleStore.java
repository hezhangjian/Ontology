package com.hezhangjian.ontology.repo;

import static com.hezhangjian.ontology.service.PipelineModels.WorkloadBundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.client.WorkloadObjectClient;
import com.hezhangjian.ontology.config.PipelineProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class WorkloadBundleStore {
    private static final Logger log = LoggerFactory.getLogger(WorkloadBundleStore.class);
    private static final int NONCE_BYTES = 12;
    private final SqlRepository jdbc;
    private final ObjectMapper json;
    private final WorkloadObjectClient objects;
    private final PipelineProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final SecretKey key;

    public WorkloadBundleStore(
            SqlRepository jdbc,
            ObjectMapper json,
            WorkloadObjectClient objects,
            PipelineProperties properties) {
        this.jdbc = jdbc;
        this.json = json;
        this.properties = properties;
        this.objects = objects;
        byte[] decoded = Base64.getDecoder().decode(properties.workloadKey().trim());
        if (decoded.length != 32) {
            throw new IllegalStateException("FLINK_WORKLOAD_KEY must be a base64-encoded 32-byte key");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String put(WorkloadBundle bundle) {
        String objectKey = bundle.kind().toLowerCase(java.util.Locale.ROOT) + "/" + bundle.workloadId() + ".json.enc";
        try {
            byte[] plaintext = json.writeValueAsBytes(bundle);
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(objectKey.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] encoded = json.writeValueAsBytes(Map.of(
                    "ciphertext", Base64.getEncoder().encodeToString(ciphertext),
                    "nonce", Base64.getEncoder().encodeToString(nonce),
                    "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext)),
                    "version", 1));
            objects.put(objectKey, encoded);
            jdbc.update("""
                    INSERT INTO control.pipeline_workloads(id,workspace_id,workload_kind,object_key,payload_hash,expires_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET object_key=excluded.object_key,payload_hash=excluded.payload_hash,
                      expires_at=excluded.expires_at,status='ACTIVE'
                    """, bundle.workloadId(), bundle.workspaceId(), bundle.kind(), objectKey,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                    Timestamp.from(bundle.expiresAt()));
            return objectKey;
        } catch (Exception cause) {
            throw new IllegalStateException("Cannot store encrypted Flink workload bundle", cause);
        }
    }

    public void markConsumed(UUID workloadId) {
        jdbc.update("UPDATE control.pipeline_workloads SET status='CONSUMED',consumed_at=coalesce(consumed_at,now()) WHERE id=?",
                workloadId);
    }

    @Scheduled(fixedDelayString = "${ontology.pipelines.workload-cleanup-interval:10m}", initialDelayString = "1m")
    void removeExpired() {
        jdbc.query("""
                SELECT id,object_key FROM control.pipeline_workloads
                WHERE expires_at<=now() AND status<>'DELETED' LIMIT 100
                """, (row, number) -> Map.of("id", row.getObject("id", UUID.class),
                "objectKey", row.getString("object_key"))).forEach(workload -> {
            try {
                objects.remove(String.valueOf(workload.get("objectKey")));
                jdbc.update("UPDATE control.pipeline_workloads SET status='DELETED',deleted_at=now() WHERE id=?",
                        workload.get("id"));
            } catch (IllegalStateException cleanupFailure) {
                log.warn("Failed to remove expired Flink workload bundle; cleanup will retry",
                        cleanupFailure);
            }
        });
    }
}
