package com.aetheris.auth.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 把权限审计事件投递到 system 服务的内部端点。
 *
 * <p>投递失败只记日志、不影响主流程（与 gateway 的 AuditLogClient 策略一致）：
 * 审计是尽力而为的旁路，权限判定本身不受审计链路影响。
 */
@Component
public class PermissionAuditClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionAuditClient.class);

    private final RestClient restClient;

    public PermissionAuditClient(
            RestClient.Builder builder,
            @Value("${aetheris.system.base-url:http://localhost:8081}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1500);
        this.restClient = builder.requestFactory(factory).baseUrl(baseUrl).build();
    }

    public void record(PermissionEventRequest event) {
        try {
            restClient.post()
                    .uri("/internal/audit/permission-events")
                    .body(event)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException error) {
            log.warn("Permission audit delivery failed: {}", error.getMessage());
        }
    }

    /** 异步尽力投递：授权/撤销等管理操作同步等待，请求路径内的拒绝则 fire-and-forget。 */
    public void recordAsync(PermissionEventRequest event, Duration timeout) {
        Thread.ofVirtual().name("perm-audit").start(() -> {
            try {
                record(event);
            } catch (RuntimeException ignored) {
                // record 已内部兜底，这里不再重复日志
            }
        });
    }
}