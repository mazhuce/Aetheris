package com.aetheris.mcp.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** MCP 调用审计：只存参数摘要与结果状态，不存原始参数。 */
@Repository
public class McpAuditRepository {

    private final JdbcTemplate jdbc;

    public McpAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(long uid, String username, String method, String toolName, String paramsDigest,
                       String status, Integer errorCode, int durationMs) {
        try {
            jdbc.update("""
                    INSERT INTO mcp.mcp_call_log
                        (uid, username, method, tool_name, params_digest, status, error_code, duration_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, uid, username, method, toolName, paramsDigest, status, errorCode, durationMs);
        } catch (RuntimeException ignored) {
            // 审计失败不能反过来阻断业务响应；生产由数据库告警兜底。
        }
    }
}
