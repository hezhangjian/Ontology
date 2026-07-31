package com.hezhangjian.ontology.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.projection")
public record ProjectionProperties(int maxRetries, Duration retryDelay) {
}
