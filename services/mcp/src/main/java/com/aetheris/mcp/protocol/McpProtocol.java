package com.aetheris.mcp.protocol;

/** JSON-RPC / MCP 错误码与协议常量。 */
public final class McpProtocol {

    public static final String JSON_RPC = "2.0";
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int TOOL_FAILED = -32000;
    public static final int AUTH_REQUIRED = -32001;
    public static final int RATE_LIMITED = -32002;

    private McpProtocol() {
    }
}
