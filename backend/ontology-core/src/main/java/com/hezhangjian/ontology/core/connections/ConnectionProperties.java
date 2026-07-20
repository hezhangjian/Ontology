package com.hezhangjian.ontology.core.connections;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "connections")
public record ConnectionProperties(
        String encryptionKey,
        int keyVersion,
        Duration testTokenTtl,
        Set<String> allowedPrivateHosts,
        boolean productionMode) {
}
