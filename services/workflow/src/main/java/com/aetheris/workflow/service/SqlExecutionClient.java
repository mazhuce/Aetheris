package com.aetheris.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * 调用 metadata 服务执行 SQL。
 *
 * <p>为什么不在 workflow 内直连数据库：metadata 已持有数据源凭据、JDBC 驱动、
 * URL 协议校验与危险连接参数黑名单。把这些复制过来等于复制一份安全边界，
 * 两边任一处遗漏就是缺口。workflow 只负责调度与状态管理。
 *
 * <p>走 {@code /internal/**} 路径，网关只路由 {@code /api/**}，因此该调用不经公网。
 */
@Component
public class SqlExecutionClient {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionClient.class);

    private final RestClient restClient;

    @Autowired
    public SqlExecutionClient(
            RestClient.Builder builder,
            @Value("${aetheris.metadata.base-url}") String baseUrl,
            @Value("${aetheris.metadata.connect-timeout:PT10S}") Duration connectTimeout,
            @Value("${aetheris.internal-token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) connectTimeout.toMillis());
        // 读超时不在这里设：加工任务可能跑很久，实际时限由任务的 timeoutSeconds 控制，
        // 在下面按次请求单独设置，避免用一个全局读超时误杀长任务。
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken).build();
    }

    public SqlExecutionClient(RestClient.Builder builder, String baseUrl, Duration connectTimeout) {
        this(builder, baseUrl, connectTimeout, "");
    }

    /**
     * 在指定数据源上执行写入语句。
     *
     * @param timeoutSeconds 语句超时；同时用于计算 HTTP 读超时（留出余量）
     * @return 执行结果，含 affectedRows 与 durationMs
     * @throws SqlExecutionException 执行失败，message 已是安全文案
     */
    public ExecutionResult execute(Long datasourceId, String sql, int timeoutSeconds) {
        // HTTP 读超时比语句超时多留 30 秒：让数据库侧的超时先触发，
        // 这样拿到的是「SQL 执行超时」而非模糊的「网络读超时」，便于定位。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout((timeoutSeconds + 30) * 1000);

        try {
            Map<String, Object> body = restClient.mutate()
                    .requestFactory(factory)
                    .build()
                    .post()
                    .uri("/internal/datasources/{id}/execute", datasourceId)
                    .body(Map.of("sql", sql, "timeoutSeconds", timeoutSeconds))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (body == null) {
                throw new SqlExecutionException("执行结果为空");
            }
            return new ExecutionResult(
                    toLong(body.get("affectedRows")),
                    toLong(body.get("durationMs")));
        } catch (RestClientResponseException error) {
            // metadata 已把驱动原文换成安全文案，这里透传它的 message。
            String message = extractMessage(error);
            log.warn("SQL execution rejected by metadata service: datasource={}, status={}",
                    datasourceId, error.getStatusCode());
            throw new SqlExecutionException(message);
        } catch (SqlExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("SQL execution call failed: datasource={}", datasourceId, error);
            throw new SqlExecutionException("无法连接元数据服务，请稍后重试");
        }
    }

    private String extractMessage(RestClientResponseException error) {
        try {
            Map<?, ?> body = error.getResponseBodyAs(Map.class);
            if (body != null && body.get("message") instanceof String message && !message.isBlank()) {
                return message;
            }
        } catch (RuntimeException ignored) {
            log.debug("Unable to parse error body from metadata service");
        }
        return "SQL 执行失败（HTTP " + error.getStatusCode().value() + "）";
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * 校验写入语句是否被允许，不执行、不连接数据源。
     *
     * <p>用于任务保存时提前反馈：否则用户填完保存成功、点运行才发现 SQL 不被接受。
     * 校验规则由 metadata 侧与执行路径共用同一份实现，不在本服务复制，避免规则漂移。
     *
     * @return 不合法时返回原因；合法时返回 null
     */
    public String validateWrite(String sql) {
        try {
            Map<String, Object> body = restClient.post()
                    .uri("/internal/sql/validate-write")
                    .body(Map.of("sql", sql))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

            if (body == null) {
                return null;
            }
            if (Boolean.TRUE.equals(body.get("valid"))) {
                return null;
            }
            Object message = body.get("message");
            return message instanceof String text ? text : "SQL 不符合任务执行的语句要求";
        } catch (RuntimeException error) {
            // Validation is a security boundary. Saving an unvalidated write task
            // makes an outage a bypass, so definition changes fail closed.
            log.warn("SQL validation unavailable, rejecting save", error);
            return "SQL 安全校验服务不可用，当前不能保存任务定义";
        }
    }

    /**
     * 执行结果。
     *
     * @param affectedRows 受影响行数；Hive 驱动不返回时为 -1 或 null，表示「未知」而非失败
     * @param durationMs   数据库侧耗时
     */
    public record ExecutionResult(Long affectedRows, Long durationMs) {
    }
}
