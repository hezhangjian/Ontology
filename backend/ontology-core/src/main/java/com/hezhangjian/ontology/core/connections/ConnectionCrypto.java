package com.hezhangjian.ontology.core.connections;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public final class ConnectionCrypto {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();
    private final ObjectMapper objectMapper;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public ConnectionCrypto(ConnectionProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        byte[] decoded = Base64.getDecoder().decode(properties.encryptionKey().trim());
        if (decoded.length != 32) {
            throw new IllegalStateException("The connection master key must decode to exactly 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public Encrypted encrypt(Map<String, String> values) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return new Encrypted(cipher.doFinal(objectMapper.writeValueAsBytes(values)), nonce);
        } catch (GeneralSecurityException | JsonProcessingException cause) {
            throw new IllegalStateException("Credential encryption failed", cause);
        }
    }

    public Map<String, String> decrypt(byte[] ciphertext, byte[] nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return objectMapper.readValue(cipher.doFinal(ciphertext), new TypeReference<>() { });
        } catch (GeneralSecurityException | java.io.IOException cause) {
            throw new IllegalStateException("Credential decryption failed", cause);
        }
    }

    public String fingerprint(Object value) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(canonicalize(value));
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (GeneralSecurityException | JsonProcessingException cause) {
            throw new IllegalStateException("Configuration fingerprint failed", cause);
        }
    }

    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonicalize).toList();
        }
        if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            return java.util.stream.IntStream.range(0, length)
                    .mapToObj(index -> canonicalize(java.lang.reflect.Array.get(value, index))).toList();
        }
        return value;
    }

    public String issueTestToken(String subject, String type, String fingerprint, String status, Instant expiresAt) {
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", subject);
            claims.put("type", type);
            claims.put("fingerprint", fingerprint);
            claims.put("status", status);
            claims.put("exp", expiresAt.getEpochSecond());
            String payload = URL_ENCODER.encodeToString(objectMapper.writeValueAsBytes(claims));
            return payload + "." + URL_ENCODER.encodeToString(hmac(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (JsonProcessingException cause) {
            throw new IllegalStateException("Test token creation failed", cause);
        }
    }

    public TestToken verifyTestToken(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.");
            if (parts.length != 2 || !MessageDigest.isEqual(hmac(parts[0].getBytes(StandardCharsets.US_ASCII)), URL_DECODER.decode(parts[1]))) {
                throw new ConnectionProblem("TEST_TOKEN_INVALID", "连接测试凭证无效，请重新测试");
            }
            Map<String, Object> claims = objectMapper.readValue(URL_DECODER.decode(parts[0]), new TypeReference<>() { });
            TestToken result = new TestToken(
                    String.valueOf(claims.get("sub")),
                    String.valueOf(claims.get("type")),
                    String.valueOf(claims.get("fingerprint")),
                    String.valueOf(claims.get("status")),
                    Instant.ofEpochSecond(((Number) claims.get("exp")).longValue()));
            if (!result.expiresAt().isAfter(Instant.now())) {
                throw new ConnectionProblem("TEST_TOKEN_EXPIRED", "连接测试结果已过期，请重新测试");
            }
            return result;
        } catch (IllegalArgumentException | java.io.IOException cause) {
            throw new ConnectionProblem("TEST_TOKEN_INVALID", "连接测试凭证无效，请重新测试");
        }
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException cause) {
            throw new IllegalStateException("Test token signing failed", cause);
        }
    }

    public record Encrypted(byte[] ciphertext, byte[] nonce) { }
    public record TestToken(String subject, String type, String fingerprint, String status, Instant expiresAt) { }
}
