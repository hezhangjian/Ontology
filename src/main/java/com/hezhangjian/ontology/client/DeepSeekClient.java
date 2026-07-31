package com.hezhangjian.ontology.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hezhangjian.ontology.config.AgentProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public final class DeepSeekClient {
    private final String apiKey;
    private final String model;
    private final WebClient web;
    private final ObjectMapper json;

    public DeepSeekClient(
            WebClient.Builder builder, ObjectMapper json, AgentProperties properties) {
        this.apiKey = properties.deepseek().apiKey();
        this.model = properties.deepseek().model();
        this.json = json;
        this.web = builder.baseUrl(properties.deepseek().baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + this.apiKey).build();
    }

    public Map<String, Object> complete(
            List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("DeepSeek API Key 未配置");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        body.put("model", model);
        body.put("stream", false);
        body.put("temperature", 0.1);
        body.put("tools", tools);
        Map<String, Object> response = web.post().uri("/chat/completions").bodyValue(body).retrieve()
                .bodyToMono(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .block(Duration.ofSeconds(90));
        if (response == null) throw new IllegalStateException("DeepSeek 返回空响应");
        List<Map<String, Object>> choices = json.convertValue(response.get("choices"), new TypeReference<>() { });
        if (choices.isEmpty()) throw new IllegalStateException("DeepSeek 未返回候选结果");
        return json.convertValue(choices.getFirst().get("message"), new TypeReference<>() { });
    }
}
