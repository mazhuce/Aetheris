package com.aetheris.mcp.client;

/** 工具执行的可预期失败：返回结构化错误，不把堆栈或内部路径暴露给客户端。 */
public class McpToolException extends RuntimeException {

    private final String code;

    public McpToolException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static McpToolException unauthorized() {
        return new McpToolException("AUTH_REQUIRED", "该工具需要经过网关认证的 Aetheris 用户会话。");
    }

    public static McpToolException unavailable(String detail) {
        return new McpToolException("UPSTREAM_UNAVAILABLE", detail);
    }
}
