package com.aetheris.aiagent.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 对话请求。
 *
 * @param conversationId 会话 id
 * @param messages       对话历史，末条必须是用户提问
 * @param dataMode       是否启用数据问答模式：启用后 AI 可调用工具检索元数据并执行只读查询。
 *                       默认关闭，因为工具调用会多花几轮模型请求与时间，
 *                       普通咨询不需要付这个代价。
 */
public record ChatRequest(
        @NotNull Long conversationId,
        @NotEmpty List<ChatMessageDto> messages,
        Boolean dataMode) {

    /** 未显式指定时按关闭处理。 */
    public boolean isDataMode() {
        return Boolean.TRUE.equals(dataMode);
    }
}
