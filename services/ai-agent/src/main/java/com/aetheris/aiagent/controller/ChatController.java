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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 20;
    private static final int MAX_MESSAGES = 50;
    private static final int MAX_TOTAL_CHARS = 20000;
    private static final String USER_HEADER = "X-User-Name";

    private final RestClient restClient;
    private final String model;
    private final ChatConversationService conversationService;
    private final ChatMessageService messageService;
    private final AtomicInteger requestCounter = new AtomicInteger();
    private volatile long windowStart = System.currentTimeMillis();

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
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(60000);
        this.restClient = builder
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @GetMapping("/conversations")
    public List<ChatConversation> listConversations(@RequestHeader(USER_HEADER) String username) {
        return conversationService.list(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getOwnerUsername, username)
                        .orderByDesc(ChatConversation::getUpdatedAt));
    }

    @PostMapping("/conversations")
    public ChatConversation createConversation(@RequestHeader(USER_HEADER) String username) {
        ChatConversation conversation = new ChatConversation();
        conversation.setOwnerUsername(username);
        conversation.setTitle("新对话");
        conversationService.save(conversation);
        return conversation;
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<ChatMessage>> listMessages(
            @PathVariable Long id,
            @RequestHeader(USER_HEADER) String username) {
        if (findOwnedConversation(id, username) == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(messageService.list(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getConversationId, id)
                        .orderByAsc(ChatMessage::getId)));
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable Long id,
            @RequestHeader(USER_HEADER) String username) {
        if (findOwnedConversation(id, username) == null) {
            return ResponseEntity.notFound().build();
        }
        boolean removed = conversationService.removeById(id);
        if (removed) {
            messageService.remove(
                    new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getConversationId, id));
        }
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestHeader(USER_HEADER) String username,
            @Valid @RequestBody ChatRequest request) {
        ChatConversation conversation = findOwnedConversation(request.conversationId(), username);
        if (conversation == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("role", "assistant", "content", "会话不存在，请刷新后重试"));
        }

        if (!allowRequest()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("role", "assistant", "content", "请求过于频繁，请稍后再试"));
        }

        List<ChatMessageDto> messages = request.messages();
        if (messages.size() > MAX_MESSAGES) {
            return ResponseEntity.badRequest()
                    .body(Map.of("role", "assistant", "content", "单次消息条数过多"));
        }
        int totalChars = messages.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length())
                .sum();
        if (totalChars > MAX_TOTAL_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("role", "assistant", "content", "单次内容过长"));
        }

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
            log.warn("DeepSeek call failed", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "role", "assistant",
                            "content", "AI 服务暂时不可用，请稍后重试",
                            "conversationId", request.conversationId()
                    ));
        }
    }

    private boolean allowRequest() {
        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000L) {
            windowStart = now;
            requestCounter.set(0);
        }
        return requestCounter.incrementAndGet() <= MAX_REQUESTS_PER_MINUTE;
    }

    private ChatConversation findOwnedConversation(Long id, String username) {
        return conversationService.getOne(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getId, id)
                        .eq(ChatConversation::getOwnerUsername, username));
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
