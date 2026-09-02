package com.aetheris.workflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 工作流事件通知客户端：任务实例最终失败（重试耗尽）时写入 system 服务的通知表。
 *
 * <p>通知失败必须静默：通知是「锦上添花」，不能因为 system 服务不可用
 * 反过来拖垮任务实例的结果落库。
 */
@Component
public class NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(NotificationClient.class);

    private final RestClient restClient;

    public NotificationClient(
            RestClient.Builder builder,
            @Value("${aetheris.system.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(5_000);
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl).build();
    }

    public void notifyAdmin(Long taskId, String bizDate, String level, String title, String message) {
        try {
            restClient.post()
                    .uri("/internal/notifications")
                    .body(Map.of(
                            "recipient", "admin",
                            "source", "workflow",
                            "sourceId", taskId,
                            "level", level,
                            "title", title,
                            "message", message,
                            "linkPath", "/development/workflow"))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException error) {
            log.warn("Failed to send workflow notification for task {}: {}", taskId, error.getMessage());
        }
    }
}
