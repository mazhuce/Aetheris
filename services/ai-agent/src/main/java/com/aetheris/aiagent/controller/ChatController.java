package com.aetheris.aiagent.controller;

import com.aetheris.aiagent.dto.ChatMessageDto;
import com.aetheris.aiagent.dto.ChatRequest;
import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.entity.ChatMessage;
import com.aetheris.aiagent.service.ChatConversationService;
import com.aetheris.aiagent.service.ChatMessageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private final RestClient restClient;
    private final String model;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;

    public ChatController(
            RestClient.Builder builder,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.model}") String model,
            ChatConversationService conversationService,
            ChatMessageService messageService) {
        this.model = model;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @GetMapping("/conversations")
    public List<ChatConversation> listConversations() {
        return conversationService.list(
                new LambdaQueryWrapper<ChatConversation>().orderByDesc(ChatConversation::getUpdatedAt));
    }

    @PostMapping("/conversations")
    public ChatConversation createConversation() {
        ChatConversation conversation = new ChatConversation();
        conversation.setTitle("新对话");
        conversationService.save(conversation);
        return conversation;
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<ChatMessage>> listMessages(@PathVariable Long id) {
        if (conversationService.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messageService.list(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .orderByAsc(ChatMessage::getId)));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(@PathVariable Long id) {
        boolean removed = conversationService.removeById(id);
        if (removed) {
            messageService.remove(
                    new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, id));
        }
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@Valid @RequestBody ChatRequest request) {
        ChatConversation conversation = conversationService.getById(request.conversationId());
        if (conversation == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("role", "assistant", "content", "会话不存在，请刷新后重试"));
        }

        List<ChatMessageDto> messages = request.messages();
        ChatMessageDto lastUser = messages.get(messages.size() - 1);
        saveMessage(request.conversationId(), "user", lastUser.content());

        if (conversation.getTitle() == null || conversation.getTitle().isBlank() || "新对话".equals(conversation.getTitle())) {
            conversation.setTitle(truncate(lastUser.content(), 30));
        }
        conversation.setUpdatedAt(OffsetDateTime.now());
        conversationService.updateById(conversation);

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "stream", false
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String content = response.path("choices").get(0).path("message").path("content").asText();
            saveMessage(request.conversationId(), "assistant", content);
            return ResponseEntity.ok(Map.of(
                    "role", "assistant",
                    "content", content,
                    "model", response.path("model").asText(model),
                    "conversationId", request.conversationId()
            ));
        } catch (RestClientException | NullPointerException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "role", "assistant",
                            "content", "AI 服务暂时不可用：" + e.getMessage(),
                            "conversationId", request.conversationId()
                    ));
        }
    }

    private void saveMessage(Long conversationId, String role, String content) {
        ChatMessage message = new ChatMessage();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content);
        messageService.save(message);
    }

    private String truncate(String text, int max) {
        if (text == null) return "新对话";
        String cleaned = text.replaceAll("\\s+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max) + "…" : cleaned;
    }
}
