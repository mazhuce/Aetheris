package com.aetheris.mcp.client;

import com.aetheris.mcp.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 未安装 v2 密钥时的显式降级：平台数据工具不可用，而不是伪造数据。 */
public class UnavailablePlatformReadClient implements PlatformReadClient {

    private static McpToolException unavailable() {
        return new McpToolException("SECURITY_NOT_INSTALLED",
                "平台数据工具需要 v2 安全密钥安装；本地演示只开放学习类工具。");
    }

    @Override
    public JsonNode searchTables(CurrentUser user, String keyword, String layer) {
        throw unavailable();
    }

    @Override
    public JsonNode describeTable(CurrentUser user, long tableId) {
        throw unavailable();
    }

    @Override
    public JsonNode query(CurrentUser user, long datasourceId, String sql) {
        throw unavailable();
    }

    @Override
    public JsonNode searchMetrics(CurrentUser user, String keyword) {
        throw unavailable();
    }

    @Override
    public JsonNode queryMetric(CurrentUser user, String metricCode, List<String> dimensions, String grain,
                                String startDate, String endDate) {
        throw unavailable();
    }
}
