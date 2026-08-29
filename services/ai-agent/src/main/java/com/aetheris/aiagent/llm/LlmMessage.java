package com.aetheris.aiagent.llm;

/**
 * 一条对话消息。
 *
 * @param role    角色：user 或 assistant（system 由适配器内部注入，不从外部传入）
 * @param content 消息内容
 */
public record LlmMessage(String role, String content) {

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }

    public boolean isUser() {
        return "user".equals(role);
    }
}
