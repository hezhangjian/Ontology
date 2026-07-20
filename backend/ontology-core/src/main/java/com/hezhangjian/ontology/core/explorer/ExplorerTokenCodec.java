package com.hezhangjian.ontology.core.explorer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
final class ExplorerTokenCodec {
    private final byte[] key;
    private final ObjectMapper objectMapper;

    ExplorerTokenCodec(ExplorerProperties properties, ObjectMapper objectMapper) {
        this.key = properties.tokenSecret().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        if (key.length < 24) {
            throw new IllegalStateException("Explorer token secret must contain at least 24 bytes");
        }
    }

    String sign(Map<String, Object> claims, Instant expiresAt) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(claims);
            payload.put("exp", expiresAt.getEpochSecond());
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(payload));
            return encoded + "." + signature(encoded);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign explorer token", exception);
        }
    }

    Map<String, Object> verify(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(
                    signature(parts[0]).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII))) {
                throw invalidToken();
            }
            Map<String, Object> claims = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]), new TypeReference<>() { });
            long expiresAt = ((Number) claims.getOrDefault("exp", 0)).longValue();
            if (Instant.now().getEpochSecond() >= expiresAt) {
                throw new ResponseStatusException(HttpStatus.GONE, "令牌已过期，请重新执行查询");
            }
            return claims;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidToken();
        }
    }

    String hash(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String signature(String encoded) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
    }

    private ResponseStatusException invalidToken() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "令牌无效，请重新执行查询");
    }
}
