package com.hezhangjian.ontology.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ontology.agent")
public record AgentProperties(DeepSeek deepseek, String coreUrl, int maxToolRounds) {
    public record DeepSeek(String apiKey, String baseUrl, String model) {
    }
}
