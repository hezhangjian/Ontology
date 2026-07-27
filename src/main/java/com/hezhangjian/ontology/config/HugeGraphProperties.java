package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.hugegraph")
public record HugeGraphProperties(String host, int port) {
}
