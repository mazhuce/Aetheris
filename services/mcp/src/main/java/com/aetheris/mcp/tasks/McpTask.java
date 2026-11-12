package com.aetheris.mcp.tasks;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/** 长任务视图：审批等待、完成、驳回都通过同一张表追踪。 */
public record McpTask(long id, long uid, String username, String type, String status, Long approvalId,
                      String labCode, JsonNode payload, JsonNode result, String error,
                      Instant createdAt, Instant updatedAt) {

    public boolean terminal() {
        return "completed".equals(status) || "rejected".equals(status) || "cancelled".equals(status)
                || "failed".equals(status);
    }
}
