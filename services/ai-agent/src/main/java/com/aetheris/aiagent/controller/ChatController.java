package com.aetheris.aiagent.controller;

import com.aetheris.aiagent.dto.ChatMessage;
import com.aetheris.aiagent.dto.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private final RestClient restClient;
    private final String model;

    public ChatController(
            RestClient.Builder builder,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.model}") String model) {
        this.model = model;
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequest request) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", request.messages(),
                "stream", false
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            String content = response.path("choices").get(0).path("message").path("content").asText();
            return ResponseEntity.ok(Map.of(
                    "role", "assistant",
                    "content", content,
                    "model", response.path("model").asText(model)
            ));
        } catch (RestClientException | NullPointerException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("role", "assistant", "content", "AI 服务暂时不可用：" + e.getMessage()));
        }
    }
}
