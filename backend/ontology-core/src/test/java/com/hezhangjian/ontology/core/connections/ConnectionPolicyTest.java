package com.hezhangjian.ontology.core.connections;

import static com.hezhangjian.ontology.core.connections.ConnectionModels.DataSourceType.EXTERNAL_PULSAR;
import static com.hezhangjian.ontology.core.connections.ConnectionModels.DataSourceType.S3_CSV;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ConnectionPolicyTest {
    private final ConnectionPolicy policy = new ConnectionPolicy(
            new ConnectionProperties("unused", 1, Duration.ofMinutes(15), Set.of("minio"), false));

    @Test
    void rejectsSecretsInConfigurationAndPlatformPulsar() {
        assertThatThrownBy(() -> policy.validate(S3_CSV, Map.of("endpoint", "http://minio:9000", "password", "leak")))
                .isInstanceOf(ConnectionProblem.class).hasMessageContaining("credential");
        assertThatThrownBy(() -> policy.validate(EXTERNAL_PULSAR, Map.of(
                "serviceUrl", "pulsar://pulsar:6650", "adminUrl", "http://pulsar:8080",
                "tenant", "platform", "namespace", "ingestion")))
                .isInstanceOf(ConnectionProblem.class).hasMessageContaining("platform tenant");
    }
}
