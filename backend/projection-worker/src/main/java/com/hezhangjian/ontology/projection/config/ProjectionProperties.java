package com.hezhangjian.ontology.projection.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("projection")
public record ProjectionProperties(
        URI hugegraphGremlinUrl,
        URI hugegraphUrl,
        int maxRetries,
        URI opensearchUrl,
        URI pulsarUrl,
        Duration retryDelay) {
}
