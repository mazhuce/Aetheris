package com.aetheris.mcp.client;

import com.aetheris.mcp.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** 平台只读能力端口。M2 只暴露查询、元数据和指标，不提供任何写操作。 */
public interface PlatformReadClient {

    JsonNode searchTables(CurrentUser user, String keyword, String layer);

    JsonNode describeTable(CurrentUser user, long tableId);

    JsonNode query(CurrentUser user, long datasourceId, String sql);

    JsonNode searchMetrics(CurrentUser user, String keyword);

    JsonNode queryMetric(CurrentUser user, String metricCode, List<String> dimensions, String grain,
                         String startDate, String endDate);
}
