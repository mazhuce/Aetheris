package com.aetheris.aiagent.llm;

/**
 * 模型回复。
 *
 * @param content      回复正文
 * @param model        实际响应的模型标识，可能与请求的不同（provider 侧路由）
 * @param promptTokens 输入 token 数，取不到时为 null
 * @param outputTokens 输出 token 数，取不到时为 null
 */
public record LlmReply(String content, String model, Integer promptTokens, Integer outputTokens) {
}
