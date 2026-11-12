package com.aetheris.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** 工具执行结果：同时返回结构化内容与一段可读文本，便于不同客户端消费。 */
public record ToolResult(JsonNode structured, String text, boolean error, String errorCode) {

    public static ToolResult ok(JsonNode structured, String text) {
        return new ToolResult(structured, text, false, null);
    }

    public static ToolResult error(String code, String message) {
        return new ToolResult(null, message, true, code);
    }
}
