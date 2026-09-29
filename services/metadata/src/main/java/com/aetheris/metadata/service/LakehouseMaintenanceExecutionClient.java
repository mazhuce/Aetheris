package com.aetheris.metadata.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/** Internal metadata-to-workflow client. A missing/unknown remote result is never treated as success. */
@Component
public class LakehouseMaintenanceExecutionClient {
    private final RestClient client;

    public LakehouseMaintenanceExecutionClient(
            RestClient.Builder builder,
            @Value("${aetheris.workflow.base-url:http://localhost:8088}") String workflowBaseUrl,
            @Value("${aetheris.internal-token:}") String internalToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.client = builder.requestFactory(factory).baseUrl(workflowBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken == null ? "" : internalToken).build();
    }

    public Map<String, Object> submit(Map<String, Object> body) {
        Map<String, Object> result = client.post().uri("/internal/lakehouse/maintenance/submit")
                .body(body).retrieve().body(new ParameterizedTypeReference<>() { });
        if (result == null) throw new IllegalStateException("workflow 未返回维护执行句柄");
        return result;
    }

    public Optional<Map<String, Object>> status(String requestKey) {
        try {
            Map<String, Object> result = client.get().uri("/internal/lakehouse/maintenance/status/{key}", requestKey)
                    .retrieve().body(new ParameterizedTypeReference<>() { });
            return Optional.ofNullable(result);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound notFound) {
            return Optional.empty();
        }
    }

    public Map<String, Object> cancel(String remoteHandle) {
        Map<String, Object> result = client.delete().uri("/internal/lakehouse/maintenance/jobs/{id}", remoteHandle)
                .retrieve().body(new ParameterizedTypeReference<>() { });
        if (result == null) throw new IllegalStateException("workflow 未返回取消结果");
        return result;
    }
}
