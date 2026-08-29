package com.aetheris.aiagent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiLlmClientTest {

    @Test
    @DisplayName("未配置 API Key 时提前失败，不发起调用")
    void failsFastWhenApiKeyMissing() {
        ChatModel chatModel = mock(ChatModel.class);
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, "deepseek-chat", "", List.of());

        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(List.of(LlmMessage.user("你好"))));

        assertEquals(LlmException.Kind.NOT_CONFIGURED, error.kind());
        // 关键：不应把请求打到 provider 才发现没密钥。
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("正常返回时提取回复内容")
    void returnsReplyContent() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("上月订单最高的是家用电器")))));
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, "deepseek-chat", "sk-test", List.of());

        LlmReply reply = client.chat(List.of(LlmMessage.user("上月哪个品类订单最多")));

        assertEquals("上月订单最高的是家用电器", reply.content());
        assertEquals("deepseek-chat", reply.model());
    }

    @Test
    @DisplayName("provider 限流被识别为 RATE_LIMITED")
    void classifiesRateLimit() {
        assertEquals(LlmException.Kind.RATE_LIMITED,
                kindFor(new RuntimeException("429 Too Many Requests")));
    }

    @Test
    @DisplayName("超时被识别为 UNAVAILABLE")
    void classifiesTimeout() {
        assertEquals(LlmException.Kind.UNAVAILABLE,
                kindFor(new RuntimeException("Read timed out")));
    }

    @Test
    @DisplayName("嵌套异常链里的关键字也能识别")
    void classifiesNestedCause() {
        RuntimeException wrapped = new RuntimeException("call failed",
                new RuntimeException("connect timed out"));

        assertEquals(LlmException.Kind.UNAVAILABLE, kindFor(wrapped));
    }

    @Test
    @DisplayName("未知失败归入 UNKNOWN，且不外泄 provider 原文")
    void classifiesUnknownWithoutLeakingDetails() {
        ChatModel chatModel = mock(ChatModel.class);
        String secretish = "request body contained sk-live-abcdef123456";
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException(secretish));
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, "deepseek-chat", "sk-test", List.of());

        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(List.of(LlmMessage.user("你好"))));

        assertEquals(LlmException.Kind.UNKNOWN, error.kind());
        // provider 原始报文可能含请求内容或密钥片段，不能出现在对外消息里。
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("sk-live"));
    }

    @Test
    @DisplayName("空回复视为失败，而不是返回空字符串")
    void treatsBlankReplyAsFailure() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("   ")))));
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, "deepseek-chat", "sk-test", List.of());

        LlmException error = assertThrows(LlmException.class,
                () -> client.chat(List.of(LlmMessage.user("你好"))));

        assertEquals(LlmException.Kind.UNKNOWN, error.kind());
    }

    private LlmException.Kind kindFor(RuntimeException thrown) {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(thrown);
        SpringAiLlmClient client = new SpringAiLlmClient(chatModel, "deepseek-chat", "sk-test", List.of());

        return assertThrows(LlmException.class,
                () -> client.chat(List.of(LlmMessage.user("你好")))).kind();
    }
}
