package com.aetheris.aiagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("检索到表时返回表清单")
    void returnsMatchedTables() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.searchTables(anyString(), any())).thenReturn(List.of(
                Map.of("id", 1, "fullName", "ads.ads_order_stats", "layer", "ads",
                        "comment", "订单核心指标日报")));
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.searchTables("订单", null);

        assertTrue(result.contains("ads.ads_order_stats"));
        assertTrue(result.contains("订单核心指标日报"), "表注释必须带上，它是模型判断口径的依据");
    }

    @Test
    @DisplayName("已发布指标优先返回统一口径提示")
    void returnsPublishedMetrics() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.searchMetrics("成交金额")).thenReturn(List.of(
                Map.of("metricCode", "GMV", "metricName", "成交金额", "version", 3)));
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.searchMetrics("成交金额");

        assertTrue(result.contains("GMV"));
        assertTrue(result.contains("优先使用 queryMetric"));
    }

    @Test
    @DisplayName("指标查询参数完整透传到统一语义服务")
    void delegatesSemanticMetricQuery() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.queryMetric(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Map.of("metricCode", "GMV", "rowCount", 1));
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.queryMetric("GMV", List.of("REGION"), "month", "2026-01-01", "2026-08-31");

        verify(client).queryMetric("GMV", List.of("REGION"), "month", "2026-01-01", "2026-08-31");
        assertTrue(result.contains("GMV"));
    }

    @Test
    @DisplayName("检索无结果时给出改写建议，而不是返回空让模型卡住")
    void returnsHintWhenNoMatch() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.searchTables(anyString(), any())).thenReturn(List.of());
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.searchTables("不存在的关键词", null);

        // 只返回空数组的话，模型往往会直接放弃或凭空编造表名。
        assertTrue(result.contains("hint"));
        assertTrue(result.contains("换个关键词"));
    }

    @Test
    @DisplayName("层级过滤参数被透传")
    void passesLayerFilter() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.searchTables(anyString(), anyString())).thenReturn(List.of());
        DataTools tools = new DataTools(client, objectMapper);

        tools.searchTables("订单", "ads");

        verify(client).searchTables("订单", "ads");
    }

    @Test
    @DisplayName("表结构包含字段注释")
    void describeIncludesColumnComments() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.describeTable(anyLong())).thenReturn(Map.of(
                "fullName", "dwd.dwd_order",
                "columns", List.of(
                        Map.of("name", "pay_amount", "type", "decimal(12,2)", "comment", "订单实付金额(元)"))));
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.describeTable(7L);

        assertTrue(result.contains("pay_amount"));
        // 字段注释是 NL2SQL 准确率的关键，丢掉它模型只能靠字段名猜语义。
        assertTrue(result.contains("订单实付金额"));
    }

    @Test
    @DisplayName("查询错误原样返回给模型，便于其自行改写重试")
    void surfacesQueryErrorToModel() {
        AgentToolClient client = mock(AgentToolClient.class);
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.query(6L, "DELETE FROM dwd.dwd_order");

        // 抛异常会中断整轮对话；返回 error 能让模型看到原因并改写。
        assertTrue(result.contains("error"));
        assertTrue(result.contains("仅支持 SELECT"));
        verify(client, never()).query(anyLong(), anyString());
    }

    @Test
    @DisplayName("查询结果被完整透传")
    void returnsQueryResult() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.query(eq(6L), anyString())).thenReturn(Map.of(
                "columns", List.of("category_name", "gmv"),
                "rows", List.of(List.of("家用电器", 43094480.70)),
                "rowCount", 1));
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.query(6L, "SELECT category_name, gmv FROM ads.ads_category_stats");

        assertTrue(result.contains("家用电器"));
        assertTrue(result.contains("category_name"));
        assertTrue(result.contains("\"rowCount\":1"));
    }

    @Test
    @DisplayName("查询工具拒绝多语句，即使第一条是 SELECT")
    void rejectsMultipleStatementsBeforeCallingMetadata() {
        AgentToolClient client = mock(AgentToolClient.class);
        DataTools tools = new DataTools(client, objectMapper);

        String result = tools.query(6L, "SELECT 1; DELETE FROM dwd.dwd_order");

        assertTrue(result.contains("单条只读 SQL"));
        verify(client, never()).query(anyLong(), anyString());
    }

    @Test
    @DisplayName("工具方法不抛异常，客户端故障也返回结构化结果")
    void neverThrows() {
        AgentToolClient client = mock(AgentToolClient.class);
        when(client.searchTables(any(), any())).thenReturn(List.of());
        when(client.describeTable(anyLong())).thenReturn(Map.of("error", "读取表结构失败"));
        when(client.query(anyLong(), anyString())).thenReturn(Map.of("error", "查询执行失败"));
        DataTools tools = new DataTools(client, objectMapper);

        // 三个工具在底层失败时都应返回可读文本而非抛异常，否则整轮对话直接中断。
        assertEquals(String.class, tools.searchTables("x", null).getClass());
        assertTrue(tools.describeTable(1L).contains("error"));
        assertTrue(tools.query(1L, "SELECT 1").contains("error"));
    }
}
