package com.aetheris.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Internal, token-authenticated quality execution client used by mixed DAGs. */
@Component
public class QualityExecutionClient {
    private final RestClient client;

    public QualityExecutionClient(RestClient.Builder builder,
                                  @Value("${aetheris.quality.base-url:http://localhost:8084}") String baseUrl,
                                  @Value("${aetheris.internal-token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(120_000);
        this.client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token == null ? "" : token).build();
    }

    public QualityResult execute(long ruleId, String triggeredBy) {
        try {
            QualityResult result = client.post().uri("/internal/quality/rules/{id}/run", ruleId)
                    .body(Map.of("triggeredBy", triggeredBy == null ? "workflow" : triggeredBy))
                    .retrieve().body(QualityResult.class);
            if (result == null) throw new IllegalStateException("质量服务返回空结果");
            return result;
        } catch (RuntimeException error) {
            throw new IllegalStateException("质量服务执行失败", error);
        }
    }

    public QualityResult latest(long ruleId) {
        try {
            return client.get().uri("/internal/quality/rules/{id}/latest", ruleId)
                    .retrieve().body(QualityResult.class);
        } catch (RuntimeException error) {
            throw new IllegalStateException("无法核对质量门禁结果", error);
        }
    }

    public record QualityResult(Long id, Long ruleId, String status, Long violationCount,
                                Long thresholdValue, Long durationMs, String message,
                                java.time.OffsetDateTime finishedAt) { }
}
