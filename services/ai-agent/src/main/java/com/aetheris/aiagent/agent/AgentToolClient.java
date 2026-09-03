package com.aetheris.aiagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 调用 metadata 服务的 agent 工具端点。
 *
 * <p>agent 不直连数据库，与 workflow 的取舍一致：凭据、JDBC 驱动、只读守卫都在 metadata，
 * 复制过来等于复制一份安全边界。走 {@code /internal/**} 路径，网关不路由，外部不可达。
 */
@Component
public class AgentToolClient {

    private static final Logger log = LoggerFactory.getLogger(AgentToolClient.class);
    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public AgentToolClient(
            RestClient.Builder builder,
            @Value("${aetheris.metadata.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        // 工具调用发生在用户等待回复的过程中，读超时不能太长，
        // 否则一个慢查询会让整轮对话卡住。查询本身在 metadata 侧还有 30 秒的语句超时。
        factory.setReadTimeout(45_000);
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl).build();
    }

    /** 按关键词与层级检索表。失败时返回空列表而非抛异常——让 agent 能继续尝试别的工具。 */
    public List<Map<String, Object>> searchTables(String keyword, String layer) {
        try {
            List<Map<String, Object>> result = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/agent/tables/search")
                            .queryParam("keyword", keyword)
                            .queryParam("layer", layer)
                            .build())
                    .retrieve()
                    .body(LIST_TYPE);
            return result == null ? List.of() : result;
        } catch (RuntimeException error) {
            log.warn("Tool searchTables failed: keyword={}, layer={}", keyword, layer, error);
            return List.of();
        }
    }

    /** 读取表结构。失败时返回带 error 字段的 Map，让 agent 知道原因并自行调整。 */
    public Map<String, Object> describeTable(Long tableId) {
        try {
            Map<String, Object> result = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/agent/tables/describe")
                            .queryParam("tableId", tableId)
                            .build())
                    .retrieve()
                    .body(MAP_TYPE);
            return result == null ? Map.of("error", "未获取到表结构") : result;
        } catch (RuntimeException error) {
            log.warn("Tool describeTable failed: tableId={}", tableId, error);
            return Map.of("error", "读取表结构失败");
        }
    }

    /** 执行只读查询。SQL 由 metadata 侧的 validateReadOnlySql 校验，此处不放宽任何限制。 */
    public Map<String, Object> query(Long datasourceId, String sql) {
        try {
            Map<String, Object> result = restClient.post()
                    .uri("/internal/agent/query")
                    .body(Map.of("datasourceId", datasourceId, "sql", sql))
                    .retrieve()
                    .body(MAP_TYPE);
            return result == null ? Map.of("error", "查询无返回") : result;
        } catch (RuntimeException error) {
            log.warn("Tool query failed: datasourceId={}", datasourceId, error);
            return Map.of("error", "查询执行失败，请检查 SQL 或数据源连通性");
        }
    }

    /** 检索企业已发布指标；优先复用口径，避免模型临时发明统计逻辑。 */
    public List<Map<String, Object>> searchMetrics(String keyword) {
        try {
            List<Map<String, Object>> result = restClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/semantic/metrics")
                            .queryParam("keyword", keyword == null ? "" : keyword)
                            .build())
                    .retrieve()
                    .body(LIST_TYPE);
            return result == null ? List.of() : result;
        } catch (RuntimeException error) {
            log.warn("Tool searchMetrics failed: keyword={}", keyword, error);
            return List.of();
        }
    }

    /** 通过统一语义服务查询指标；底层 SQL 由 metadata 编译并校验。 */
    public Map<String, Object> queryMetric(String metricCode, List<String> dimensions, String timeGrain,
                                           String startDate, String endDate) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("dimensions", dimensions == null ? List.of() : dimensions);
            if (timeGrain != null && !timeGrain.isBlank()) body.put("timeGrain", timeGrain);
            if (startDate != null && !startDate.isBlank()) body.put("startDate", startDate);
            if (endDate != null && !endDate.isBlank()) body.put("endDate", endDate);
            body.put("limit", 100);
            Map<String, Object> result = restClient.post()
                    .uri("/internal/semantic/metrics/{code}/query", metricCode)
                    .body(body)
                    .retrieve()
                    .body(MAP_TYPE);
            return result == null ? Map.of("error", "指标查询无返回") : result;
        } catch (RuntimeException error) {
            log.warn("Tool queryMetric failed: metricCode={}", metricCode, error);
            return Map.of("error", "指标查询失败，请检查指标状态、维度或日期条件");
        }
    }
}
