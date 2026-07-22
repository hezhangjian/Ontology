package com.hezhangjian.ontology.core.connections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ConnectionCryptoTest {
    private final ConnectionCrypto crypto = new ConnectionCrypto(
            new ConnectionProperties(Base64.getEncoder().encodeToString(new byte[32]), 1, Duration.ofMinutes(15), Set.of(), false,
                    URI.create("http://minio:9000"), "import-staging", "access-key", "secret-key", 50, 25_000_000, 100_000_000),
            new ObjectMapper());

    @Test
    void encryptsCredentialAndRejectsModifiedTestToken() {
        ConnectionCrypto.Encrypted encrypted = crypto.encrypt(Map.of("password", "never-return-this"));

        assertThat(new String(encrypted.ciphertext())).doesNotContain("never-return-this");
        assertThat(crypto.decrypt(encrypted.ciphertext(), encrypted.nonce())).containsEntry("password", "never-return-this");

        String token = crypto.issueTestToken("builder", "S3_CSV", "fingerprint", "HEALTHY", Instant.now().plusSeconds(60));
        assertThat(crypto.verifyTestToken(token).subject()).isEqualTo("builder");
        assertThatThrownBy(() -> crypto.verifyTestToken(token + "x")).isInstanceOf(ConnectionProblem.class);
    }

    @Test
    void fingerprintsNestedMapsCanonically() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("endpoint", "https://example.test");
        first.put("options", new LinkedHashMap<>(Map.of("z", 1, "a", 2)));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("options", new LinkedHashMap<>(Map.of("a", 2, "z", 1)));
        second.put("endpoint", "https://example.test");

        assertThat(crypto.fingerprint(first)).isEqualTo(crypto.fingerprint(second));
    }
}
