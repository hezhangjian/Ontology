package com.hezhangjian.ontology.core.dashboards;

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
final class DashboardTokenCodec {
    private final byte[] key;
    private final ObjectMapper objectMapper;

    DashboardTokenCodec(DashboardProperties properties, ObjectMapper objectMapper) {
        this.key = properties.tokenSecret().getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
        if (key.length < 24) throw new IllegalStateException("Dashboard token secret must contain at least 24 bytes");
    }

    String sign(Map<String, Object> claims, Instant expiresAt) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(claims);
            payload.put("exp", expiresAt.getEpochSecond());
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload));
            return encoded + "." + signature(encoded);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign dashboard token", exception);
        }
    }

    Map<String, Object> verify(String token) {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.US_ASCII),
                    parts[1].getBytes(StandardCharsets.US_ASCII))) throw invalid();
            Map<String, Object> claims = objectMapper.readValue(Base64.getUrlDecoder().decode(parts[0]), new TypeReference<>() { });
            if (Instant.now().getEpochSecond() >= ((Number) claims.getOrDefault("exp", 0)).longValue()) {
                throw new ResponseStatusException(HttpStatus.GONE, "下钻令牌已过期，请刷新组件");
            }
            return claims;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private String signature(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.US_ASCII)));
    }

    private ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "下钻令牌无效，请刷新组件");
    }
}
