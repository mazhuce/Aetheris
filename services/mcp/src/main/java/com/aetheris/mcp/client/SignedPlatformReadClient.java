package com.aetheris.mcp.client;

import com.aetheris.mcp.security.CurrentUser;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 通过签名工作负载 + 转发用户证明访问 metadata-service 的只读接口。 */
public class SignedPlatformReadClient implements PlatformReadClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final SignedHttpClient transport;

    public SignedPlatformReadClient(SignedHttpClient transport) {
        this.transport = transport;
    }

    @Override
    public JsonNode searchTables(CurrentUser user, String keyword, String layer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", keyword == null ? "" : keyword);
        body.put("layer", layer == null ? "" : layer);
        return post(user, "agent/tables/search", "metadata.agent.search", body);
    }

    @Override
    public JsonNode describeTable(CurrentUser user, long tableId) {
        return post(user, "agent/tables/describe", "metadata.agent.describe", Map.of("tableId", tableId));
    }

    @Override
    public JsonNode query(CurrentUser user, long datasourceId, String sql) {
        return post(user, "agent/query", "metadata.user.sql-query",
                Map.of("datasourceId", datasourceId, "sql", sql));
    }

    @Override
    public JsonNode searchMetrics(CurrentUser user, String keyword) {
        return post(user, "semantic/metrics/list", "metadata.agent.metric-list",
                Map.of("keyword", keyword == null ? "" : keyword));
    }

    @Override
    public JsonNode queryMetric(CurrentUser user, String metricCode, List<String> dimensions, String grain,
                                String startDate, String endDate) {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("dimensions", dimensions == null ? List.of() : List.copyOf(dimensions));
        query.put("timeGrain", grain);
        query.put("startDate", startDate);
        query.put("endDate", endDate);
        query.put("limit", 100);
        return post(user, "semantic/metrics/query", "metadata.user.metric-query",
                Map.of("metricCode", metricCode, "query", query));
    }

    private JsonNode post(CurrentUser user, String path, String operation, Object body) {
        if (user == null || !user.hasProof()) throw McpToolException.unauthorized();
        try {
            byte[] payload = JSON.writeValueAsBytes(body);
            var request = new SignedHttpClient.Request("metadata", "POST", "/internal/v2/" + path, operation,
                    payload, user.compactProof());
            var pending = transport.sendAsync(request);
            SignedHttpClient.Response response;
            try {
                response = pending.get(45, TimeUnit.SECONDS);
            } finally {
                if (!pending.isDone()) pending.cancel(true);
            }
            if (response.status() != 200) {
                throw McpToolException.unavailable("平台返回状态 " + response.status());
            }
            return JSON.readTree(response.body());
        } catch (McpToolException expected) {
            throw expected;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw McpToolException.unavailable("平台调用被中断");
        } catch (Exception failure) {
            throw McpToolException.unavailable("平台调用失败：" + safe(failure));
        }
    }

    private static String safe(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
