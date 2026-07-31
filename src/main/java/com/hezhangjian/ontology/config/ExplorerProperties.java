package com.hezhangjian.ontology.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.explorer")
public record ExplorerProperties(
        String tokenSecret,
        Duration tokenTtl) {
}
