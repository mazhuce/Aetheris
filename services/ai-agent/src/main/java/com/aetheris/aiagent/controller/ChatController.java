package com.aetheris.aiagent.controller;

import com.aetheris.aiagent.dto.ChatRequest;
import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.entity.ChatMessage;
import com.aetheris.aiagent.llm.LlmException;
import com.aetheris.aiagent.llm.LlmReply;
import com.aetheris.aiagent.service.ChatService;
import com.aetheris.aiagent.service.UserRateLimiter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话接口。
 *
 * <p>只做协议转换：解析请求、限流、把结果映射成 HTTP 响应。会话编排与模型调用分别在
 * {@link ChatService} 与 LlmClient 里，便于单测与后续扩展工具调用循环。
 */
@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final String USER_HEADER = "X-User-Name";

    private final ChatService chatService;
    private final UserRateLimiter rateLimiter;

    public ChatController(ChatService chatService, UserRateLimiter rateLimiter) {
        this.chatService = chatService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/conversations")
    public List<ChatConversation> listConversations(@RequestHeader(USER_HEADER) String username) {
        return chatService.listConversations(username);
    }

    @PostMapping("/conversations")
    public ChatConversation createConversation(@RequestHeader(USER_HEADER) String username) {
        return chatService.createConversation(username);
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<ChatMessage>> listMessages(
            @PathVariable Long id,
            @RequestHeader(USER_HEADER) String username) {
        List<ChatMessage> messages = chatService.listMessages(id, username);
        return messages == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(messages);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable Long id,
            @RequestHeader(USER_HEADER) String username) {
        return chatService.deleteConversation(id, username)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestHeader(USER_HEADER) String username,
            @Valid @RequestBody ChatRequest request) {
        ChatConversation conversation = chatService.findOwned(request.conversationId(), username);
        if (conversation == null) {
            return ResponseEntity.badRequest().body(assistant("会话不存在，请刷新后重试", null));
        }

        if (!rateLimiter.tryAcquire(username)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(assistant("请求过于频繁，请稍后再试", request.conversationId()));
        }

        String invalidReason = chatService.validate(request.messages());
        if (invalidReason != null) {
            return ResponseEntity.badRequest().body(assistant(invalidReason, request.conversationId()));
        }

        try {
            LlmReply reply = chatService.chat(conversation, request.messages(), request.isDataMode());
            Map<String, Object> body = assistant(reply.content(), request.conversationId());
            body.put("model", reply.model());
            return ResponseEntity.ok(body);
        } catch (LlmException error) {
            log.warn("Chat failed for user {}: {}", username, error.kind(), error);
            // 用户提问已落库，此处只回错误提示；刷新后用户仍能看到自己问过什么。
            return ResponseEntity.status(statusOf(error))
                    .body(assistant(error.getMessage(), request.conversationId()));
        }
    }

    private HttpStatus statusOf(LlmException error) {
        return switch (error.kind()) {
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNAVAILABLE, UNKNOWN -> HttpStatus.BAD_GATEWAY;
        };
    }

    /** 用可变 Map 以便按需补充 model 字段；Map.of 不允许后续 put。 */
    private Map<String, Object> assistant(String content, Long conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", "assistant");
        body.put("content", content);
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        return body;
    }
}
