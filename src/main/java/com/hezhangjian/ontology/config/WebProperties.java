package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.web")
public record WebProperties(int multipartMaxParts) {
}
