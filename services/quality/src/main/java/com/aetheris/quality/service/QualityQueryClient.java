package com.aetheris.quality.service;

import com.aetheris.quality.security.ServiceIdentitySigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

@Component
public class QualityQueryClient {

    private final RestClient restClient;
    private final ServiceIdentitySigner serviceIdentitySigner;

    @Autowired
    public QualityQueryClient(RestClient.Builder builder, @Value("${aetheris.metadata.base-url}") String baseUrl,
                              @Value("${aetheris.internal-token:}") String internalToken,
                              ServiceIdentitySigner serviceIdentitySigner) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(60_000);
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken).build();
        this.serviceIdentitySigner = serviceIdentitySigner;
    }

    public QualityQueryClient(RestClient.Builder builder, String baseUrl, String internalToken) {
        this(builder, baseUrl, internalToken, null);
    }

    public QualityQueryClient(RestClient.Builder builder, String baseUrl) {
        this(builder, baseUrl, "", null);
    }

    public long violationCount(Long datasourceId, String sql) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri("/internal/agent/query")
                    // 质量检查由用户请求触发时，沿用网关验签身份，让 metadata
                    // 的 datasource.query 权限和查询审计与主链路保持一致。
                    .header("X-Aetheris-Identity", identityHeader())
                    .body(Map.of("datasourceId", datasourceId, "sql", sql))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RuntimeException error) {
            throw new QualityExecutionException("无法连接元数据服务，请稍后重试");
        }
        if (body == null) throw new QualityExecutionException("质量查询没有返回结果");
        if (body.get("error") instanceof String message) throw new QualityExecutionException(message);
        Object rawRows = body.get("rows");
        if (!(rawRows instanceof List<?> rows) || rows.isEmpty()
                || !(rows.getFirst() instanceof List<?> first) || first.isEmpty()) {
            throw new QualityExecutionException("质量查询必须返回 violation_count");
        }
        Object value = first.getFirst();
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new QualityExecutionException("violation_count 不是整数");
        }
    }

    private String identityHeader() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            String value = servlet.getRequest().getHeader("X-Aetheris-Identity");
            if (value != null && !value.isBlank()) return value;
        }
        return serviceIdentitySigner == null ? "" : serviceIdentitySigner.sign();
    }

    public static class QualityExecutionException extends RuntimeException {
        public QualityExecutionException(String message) { super(message); }
    }
}
