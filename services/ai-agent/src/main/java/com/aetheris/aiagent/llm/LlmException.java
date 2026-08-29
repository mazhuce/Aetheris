package com.aetheris.aiagent.llm;

/**
 * 模型调用失败。
 *
 * <p>刻意不携带 provider 的原始报文：错误详情可能包含请求内容或密钥片段，
 * 只向上层暴露分类与安全的提示文案，原始异常记录在日志里。
 */
public class LlmException extends RuntimeException {

    private final Kind kind;

    public LlmException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** 失败分类，决定上层返回的 HTTP 状态与提示。 */
    public enum Kind {
        /** 未配置 API Key 等本地配置问题。 */
        NOT_CONFIGURED,
        /** provider 侧限流。 */
        RATE_LIMITED,
        /** 网络超时或不可达。 */
        UNAVAILABLE,
        /** 其他未分类失败。 */
        UNKNOWN
    }
}
