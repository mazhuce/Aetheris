package com.aetheris.mcp.tools;

import com.aetheris.mcp.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.function.BiFunction;

/** 一个 MCP 工具就是一份契约：名称、描述、输入 schema、风险层级和执行器。 */
public record ToolDefinition(String name, String title, String description, Map<String, Object> inputSchema,
                             int riskLevel, BiFunction<CurrentUser, JsonNode, ToolResult> handler) {

    public ToolResult call(CurrentUser user, JsonNode arguments) {
        return handler.apply(user, arguments == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                : arguments);
    }
}
