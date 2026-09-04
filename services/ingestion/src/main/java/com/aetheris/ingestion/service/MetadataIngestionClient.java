package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MetadataIngestionClient {

    private final RestClient restClient;

    @Autowired
    public MetadataIngestionClient(
            RestClient.Builder builder,
            @Value("${aetheris.metadata.base-url}") String baseUrl,
            @Value("${aetheris.internal-token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(3_630_000);
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken).build();
    }

    public MetadataIngestionClient(RestClient.Builder builder, String baseUrl) {
        this(builder, baseUrl, "");
    }

    public TransferResult execute(IngestionTask task) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("sourceDatasourceId", task.getSourceDatasourceId());
        request.put("targetDatasourceId", task.getTargetDatasourceId());
        request.put("sourceSql", task.getSourceSql());
        request.put("targetTable", task.getTargetTable());
        request.put("writeMode", task.getWriteMode());
        request.put("batchSize", task.getBatchSize());
        try {
            Map<String, Object> result = restClient.post()
                    .uri("/internal/ingestion/execute")
                    .body(request)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (result == null || !Boolean.TRUE.equals(result.get("success"))) {
                throw new IngestionExecutionException("接入执行未返回有效结果");
            }
            return new TransferResult(number(result.get("rowsRead")), number(result.get("rowsWritten")),
                    number(result.get("durationMs")));
        } catch (RestClientResponseException error) {
            String message = "数据接入执行失败";
            try {
                Map<?, ?> body = error.getResponseBodyAs(Map.class);
                if (body != null && body.get("message") instanceof String text && !text.isBlank()) {
                    message = text;
                }
            } catch (RuntimeException ignored) {
                // 使用安全的通用错误，不透出内部连接信息。
            }
            throw new IngestionExecutionException(message);
        } catch (IngestionExecutionException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IngestionExecutionException("无法连接元数据服务，请稍后重试");
        }
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    public record TransferResult(long rowsRead, long rowsWritten, long durationMs) { }

    public static class IngestionExecutionException extends RuntimeException {
        public IngestionExecutionException(String message) { super(message); }
    }
}
