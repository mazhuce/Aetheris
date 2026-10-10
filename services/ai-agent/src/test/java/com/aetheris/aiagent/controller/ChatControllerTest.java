package com.aetheris.aiagent.controller;

import com.aetheris.aiagent.dto.ChatMessageDto;
import com.aetheris.aiagent.dto.ChatRequest;
import com.aetheris.aiagent.entity.ChatConversation;
import com.aetheris.aiagent.llm.LlmException;
import com.aetheris.aiagent.llm.LlmReply;
import com.aetheris.aiagent.service.ChatService;
import com.aetheris.aiagent.service.UserRateLimiter;
import com.aetheris.aiagent.security.AetherisIdentity;
import com.aetheris.aiagent.security.IdentityInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    @Test
    @DisplayName("创建会话归属当前登录用户")
    void assignsConversationToAuthenticatedUser() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, permissiveLimiter());

        controller.createConversation("alice");

        verify(chatService).createConversation("alice");
    }

    @Test
    @DisplayName("HTTP 入口忽略可伪造的 X-User-Name，只使用签名身份")
    void httpEntryUsesSignedIdentityInsteadOfHeader() {
        ChatService chatService = mock(ChatService.class);
        ChatController controller = new ChatController(chatService, permissiveLimiter());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/conversations");
        request.addHeader("X-User-Name", "attacker");
        request.setAttribute(IdentityInterceptor.IDENTITY_ATTRIBUTE, new AetherisIdentity(7L, "alice"));

        controller.listConversations(request);

        verify(chatService).listConversations("alice");
    }

    @Test
    @DisplayName("他人会话按不存在处理")
    void hidesConversationOwnedByAnotherUser() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.listMessages(42L, "alice")).thenReturn(null);
        ChatController controller = new ChatController(chatService, permissiveLimiter());

        assertEquals(HttpStatus.NOT_FOUND, controller.listMessages(42L, "alice").getStatusCode());
    }

    @Test
    @DisplayName("超出配额时返回 429 且不调用模型")
    void rejectsWhenRateLimited() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.findOwned(any(), anyString())).thenReturn(new ChatConversation());
        UserRateLimiter limiter = mock(UserRateLimiter.class);
        when(limiter.tryAcquire(anyString())).thenReturn(false);
        ChatController controller = new ChatController(chatService, limiter);

        var response = controller.chat("alice", request("你好"));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        // 关键：限流命中时不能已经把消息落库或调用模型，否则配额形同虚设。
        verify(chatService, never()).chat(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("会话不存在时返回 400 且不消耗配额外的模型调用")
    void rejectsUnknownConversation() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.findOwned(any(), anyString())).thenReturn(null);
        ChatController controller = new ChatController(chatService, permissiveLimiter());

        var response = controller.chat("alice", request("你好"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(chatService, never()).chat(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("校验不通过时返回 400 与具体原因")
    void rejectsInvalidRequest() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.findOwned(any(), anyString())).thenReturn(new ChatConversation());
        when(chatService.validate(any())).thenReturn("最后一条消息必须是用户提问");
        ChatController controller = new ChatController(chatService, permissiveLimiter());

        var response = controller.chat("alice", request("你好"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("最后一条消息必须是用户提问", body(response).get("content"));
        verify(chatService, never()).chat(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("成功时回显模型标识")
    void returnsReplyWithModel() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.findOwned(any(), anyString())).thenReturn(new ChatConversation());
        when(chatService.validate(any())).thenReturn(null);
        when(chatService.chat(any(), any(), anyBoolean()))
                .thenReturn(new LlmReply("你好，我能帮你查数据", "deepseek-chat", 12, 34));
        ChatController controller = new ChatController(chatService, permissiveLimiter());

        var response = controller.chat("alice", request("你好"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("你好，我能帮你查数据", body(response).get("content"));
        assertEquals("deepseek-chat", body(response).get("model"));
    }

    @Test
    @DisplayName("未配置密钥返回 503，而不是笼统的 502")
    void mapsNotConfiguredToServiceUnavailable() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusFor(LlmException.Kind.NOT_CONFIGURED));
    }

    @Test
    @DisplayName("provider 限流返回 429")
    void mapsProviderRateLimitTo429() {
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusFor(LlmException.Kind.RATE_LIMITED));
    }

    @Test
    @DisplayName("provider 不可用返回 502")
    void mapsUnavailableTo502() {
        assertEquals(HttpStatus.BAD_GATEWAY, statusFor(LlmException.Kind.UNAVAILABLE));
    }

    private HttpStatus statusFor(LlmException.Kind kind) {
        ChatService chatService = mock(ChatService.class);
        when(chatService.findOwned(any(), anyString())).thenReturn(new ChatConversation());
        when(chatService.validate(any())).thenReturn(null);
        when(chatService.chat(any(), any(), anyBoolean()))
                .thenThrow(new LlmException(kind, "调用失败", null));
        ChatController controller = new ChatController(chatService, permissiveLimiter());

        return (HttpStatus) controller.chat("alice", request("你好")).getStatusCode();
    }

    private UserRateLimiter permissiveLimiter() {
        UserRateLimiter limiter = mock(UserRateLimiter.class);
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        return limiter;
    }

    private ChatRequest request(String content) {
        return new ChatRequest(1L, List.of(new ChatMessageDto("user", content)), false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(org.springframework.http.ResponseEntity<Map<String, Object>> response) {
        return response.getBody();
    }
}
