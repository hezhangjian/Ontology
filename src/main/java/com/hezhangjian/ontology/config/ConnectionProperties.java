package com.hezhangjian.ontology.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.connections")
public record ConnectionProperties(
        String encryptionKey,
        int keyVersion,
        Duration testTokenTtl,
        Set<String> allowedPrivateHosts,
        boolean productionMode,
        String secretDirectory,
        int localCsvMaxFiles,
        long localCsvMaxFileBytes,
        long localCsvMaxTotalBytes) {
}
