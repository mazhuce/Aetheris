-- MCP 调用审计：只记录参数摘要，不落原始参数，避免把敏感值写进日志表。
CREATE TABLE IF NOT EXISTS mcp.mcp_call_log (
    id           BIGSERIAL PRIMARY KEY,
    uid          BIGINT,
    username     VARCHAR(120),
    method       VARCHAR(64) NOT NULL,
    tool_name    VARCHAR(64),
    params_digest VARCHAR(64),
    status       VARCHAR(24) NOT NULL,
    error_code   INTEGER,
    duration_ms  INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mcp_log_uid ON mcp.mcp_call_log (uid, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mcp_log_tool ON mcp.mcp_call_log (tool_name, created_at DESC);
