package com.hezhangjian.ontology.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ontology.actions")
public record ActionProperties(@Min(1) long outboxIntervalMs) {
}
