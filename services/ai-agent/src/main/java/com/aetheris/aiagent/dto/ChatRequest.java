package com.aetheris.aiagent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 对话请求。
 *
 * @param conversationId 会话 id
 * @param messages       兼容旧客户端的消息字段；仅末条 user 提问可作为输入，历史从本人会话读取
 * @param dataMode       是否启用数据问答模式：启用后 AI 可调用工具检索元数据并执行只读查询。
 *                       默认关闭，因为工具调用会多花几轮模型请求与时间，
 *                       普通咨询不需要付这个代价。
 */
public record ChatRequest(
        @NotNull Long conversationId,
        @NotEmpty @jakarta.validation.constraints.Size(max=50) @jakarta.validation.Valid
        List<@NotNull ChatMessageDto> messages,
        Boolean dataMode,
        String mode,
        @jakarta.validation.constraints.Size(max=600) String context) {
    public ChatRequest(Long conversationId, List<ChatMessageDto> messages, Boolean dataMode) {
        this(conversationId, messages, dataMode, null, null);
    }

    public ChatRequest { if(messages!=null)messages=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(messages)); }

    /** 未显式指定时按关闭处理。 */
    public boolean isDataMode() {
        return Boolean.TRUE.equals(dataMode);
    }

    /** 专家模式：固定「数据开发专家」人格，并携带页面学习上下文。 */
    public boolean isExpert() {
        return "expert".equalsIgnoreCase(mode);
    }
}
