package com.aetheris.metadata.dto;

import com.aetheris.metadata.entity.QueryHistory;
import java.time.OffsetDateTime;

/** 本人 SQL 保持可复制；旧驱动错误原文永不进入公开响应。 */
public record QueryHistoryResponse(Long id, String username, Long datasourceId, String datasourceName,
        String sqlText, String status, Long rowCount, Long durationMs, String errorMessage,
        OffsetDateTime createdAt, String requestId, String errorCode) {
    public static QueryHistoryResponse from(QueryHistory history) {
        String code = "success".equals(history.getStatus()) ? null : safeCode(history.getErrorCode());
        return new QueryHistoryResponse(history.getId(), history.getUsername(), history.getDatasourceId(),
                history.getDatasourceName(), history.getSqlText(), history.getStatus(), history.getRowCount(),
                history.getDurationMs(), message(code), history.getCreatedAt(), history.getRequestId(), code);
    }
    public static String safeCode(String value) {
        if (value == null) return "QUERY_FAILED";
        return switch (value) {
            case "SQL_POLICY_REJECTED", "QUERY_UNAVAILABLE", "TIMEOUT", "QUERY_FAILED" -> value;
            default -> "QUERY_FAILED";
        };
    }
    public static String message(String code) {
        if (code == null) return null;
        return switch (code) {
            case "SQL_POLICY_REJECTED" -> "查询被拒绝，请检查 SQL 与数据源配置";
            case "TIMEOUT" -> "查询超时，请缩小查询范围后重试";
            default -> "查询失败，请检查 SQL 与数据源配置";
        };
    }
}
