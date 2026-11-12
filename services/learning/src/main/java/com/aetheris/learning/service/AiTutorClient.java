package com.aetheris.learning.service;

import com.aetheris.learning.config.LearningProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI 兼容的 AI 导师客户端（默认 DeepSeek）。未配置 Key 或调用失败时返回空，
 * 由规则导师兜底，保证学习流程不因外部模型不可用而中断。
 */
@Component
public class AiTutorClient {

    private static final Logger log = LoggerFactory.getLogger(AiTutorClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LearningProperties properties;
    private final RestClient client;

    public AiTutorClient(LearningProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(Math.min(3000, properties.getAi().getTimeoutMs())));
        factory.setReadTimeout(Duration.ofMillis(properties.getAi().getTimeoutMs()));
        this.client = RestClient.builder()
                .baseUrl(properties.getAi().getBaseUrl().replaceAll("/+$", ""))
                .requestFactory(factory)
                .build();
    }

    public record Answer(String content, String model) {
    }

    public boolean enabled() {
        String key = properties.getAi().getApiKey();
        return key != null && !key.isBlank() && !key.startsWith("sk-local-placeholder");
    }

    public Optional<Answer> complete(String systemPrompt, String userPrompt) {
        if (!enabled()) return Optional.empty();
        try {
            JsonNode response = client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + properties.getAi().getApiKey())
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "model", properties.getAi().getModel(),
                            "temperature", 0.2,
                            "max_tokens", properties.getAi().getMaxTokens(),
                            "messages", List.of(
                                    Map.of("role", "system", "content", systemPrompt),
                                    Map.of("role", "user", "content", userPrompt))))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) return Optional.empty();
            String content = response.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) return Optional.empty();
            String model = response.path("model").asText(properties.getAi().getModel());
            return Optional.of(new Answer(trim(content), model));
        } catch (RuntimeException failure) {
            log.warn("AI tutor call failed: {}", failure.getMessage());
            return Optional.empty();
        }
    }

    private static String trim(String content) {
        String value = content.strip();
        return value.length() <= 4000 ? value : value.substring(0, 4000);
    }
}
