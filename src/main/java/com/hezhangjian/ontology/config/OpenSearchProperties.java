package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.opensearch")
public record OpenSearchProperties(String host, int port) {
}
