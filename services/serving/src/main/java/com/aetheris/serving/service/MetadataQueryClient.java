package com.aetheris.serving.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class MetadataQueryClient {

    private final RestClient restClient;

    public MetadataQueryClient(RestClient.Builder builder, @Value("${aetheris.metadata.base-url}") String baseUrl, @Value("${aetheris.internal-token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(45_000);
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl).defaultHeader("X-Internal-Token", internalToken).build();
    }

    public Map<String, Object> query(Long datasourceId, String sql) {
        Map<String, Object> body = restClient.post()
                .uri("/internal/agent/query")
                .body(Map.of("datasourceId", datasourceId, "sql", sql))
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (body == null) throw new IllegalStateException("查询结果为空");
        if (body.get("error") instanceof String message) throw new IllegalArgumentException(message);
        return body;
    }

    public Map<String, Object> queryMetric(String metricCode, Object query) {
        Map<String, Object> body = restClient.post()
                .uri("/internal/semantic/metrics/{code}/query", metricCode)
                .body(query == null ? Map.of() : query)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (body == null) throw new IllegalStateException("指标查询结果为空");
        if (body.get("error") instanceof String message) throw new IllegalArgumentException(message);
        return body;
    }

    public Map<String, Object> queryTable(Long tableId, Object query) {
        Map<String, Object> body = restClient.post()
                .uri("/internal/tables/{id}/query", tableId)
                .body(query == null ? Map.of() : query)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        if (body == null) throw new IllegalStateException("表查询结果为空");
        if (body.get("error") instanceof String message) throw new IllegalArgumentException(message);
        return body;
    }

    public Map<String, Object> publishedApiAsset(String assetCode) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri("/internal/api-assets/{code}", assetCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (body == null) throw new ApiAssetNotFoundException();
            return body;
        } catch (HttpClientErrorException.NotFound error) {
            throw new ApiAssetNotFoundException();
        } catch (RestClientException error) {
            throw new IllegalStateException("元数据服务暂时不可用，请稍后重试");
        }
    }

    public static class ApiAssetNotFoundException extends RuntimeException {
        public ApiAssetNotFoundException() { super("API 资产不存在或未发布"); }
    }
}
