package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.llm")
public record LlmProperties(String baseUrl, String model, String apiKey) {
}
