package com.aetheris.workflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Fail-closed impact analysis used when a table release is approved. */
@Component
public class MetadataImpactClient {
    private final RestClient client;

    public MetadataImpactClient(RestClient.Builder builder,
                                @Value("${aetheris.metadata.base-url:http://localhost:8082}") String baseUrl,
                                @Value("${aetheris.internal-token:}") String token) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000); factory.setReadTimeout(5000);
        client = builder.requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", token == null ? "" : token).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> impact(String table) {
        try {
            Map<String, Object> result = client.get().uri(builder -> builder.path("/internal/lineage/impact")
                    .queryParam("table", table).build()).retrieve().body(Map.class);
            if (result == null) throw new IllegalStateException("影响分析返回空结果");
            return result;
        } catch (RuntimeException error) {
            throw new IllegalStateException("影响分析服务不可用，当前不能审批表发布", error);
        }
    }
}
