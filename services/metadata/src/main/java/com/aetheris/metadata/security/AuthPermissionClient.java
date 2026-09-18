package com.aetheris.metadata.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 auth-service 内部权限端点的客户端。判定输入是经过验签的身份，
 * 判定逻辑由 auth-service 决策引擎维护（角色/授权/组），业务服务不做本地复制。
 *
 * <p>fail-closed：auth-service 不可用或超时一律返回拒绝，绝不在鉴权链路
 * 降级放行。
 */
@Component
public class AuthPermissionClient {

    private static final Logger log = LoggerFactory.getLogger(AuthPermissionClient.class);

    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final Counter checkRequests;
    private final Counter checkAllowed;
    private final Counter checkDenied;
    private final Counter checkUnavailable;
    private final Timer checkLatency;
    private final Counter batchRequests;
    private final Counter batchAllowedIds;
    private final Counter batchItems;
    private final Counter batchUnavailable;
    private final Timer batchLatency;

    @Autowired
    public AuthPermissionClient(
            RestClient.Builder builder,
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.auth.base-url:http://localhost:8087}") String authBaseUrl,
            MeterRegistry meterRegistry) {
        this(buildRestClient(builder, internalToken, authBaseUrl), meterRegistry);
    }

    private static RestClient buildRestClient(RestClient.Builder builder, String internalToken, String authBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1500);
        return builder
                .requestFactory(factory)
                .baseUrl(authBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    AuthPermissionClient(RestClient restClient) {
        this(restClient, new SimpleMeterRegistry());
    }

    AuthPermissionClient(RestClient restClient, MeterRegistry meterRegistry) {
        this.restClient = restClient;
        this.meterRegistry = meterRegistry;
        this.checkRequests = Counter.builder("aetheris.auth.permission.check.requests").register(meterRegistry);
        this.checkAllowed = Counter.builder("aetheris.auth.permission.check.allowed").register(meterRegistry);
        this.checkDenied = Counter.builder("aetheris.auth.permission.check.denied").register(meterRegistry);
        this.checkUnavailable = Counter.builder("aetheris.auth.permission.check.unavailable").register(meterRegistry);
        this.checkLatency = Timer.builder("aetheris.auth.permission.check.latency").register(meterRegistry);
        this.batchRequests = Counter.builder("aetheris.auth.permission.batch.requests").register(meterRegistry);
        this.batchAllowedIds = Counter.builder("aetheris.auth.permission.batch.allowed_ids").register(meterRegistry);
        this.batchItems = Counter.builder("aetheris.auth.permission.batch.items").register(meterRegistry);
        this.batchUnavailable = Counter.builder("aetheris.auth.permission.batch.unavailable").register(meterRegistry);
        this.batchLatency = Timer.builder("aetheris.auth.permission.batch.latency").register(meterRegistry);
    }

    /** 单条资源级权限判定（与 auth-service PermissionCheckResponse 对齐）。 */
    public Access check(String username, String resourceType, Long resourceId,
                        String action, Map<String, String> ctx) {
        checkRequests.increment();
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            PermissionCheckResponse response = restClient.post()
                    .uri("/internal/permissions/check")
                    .body(checkRequestBody(username, resourceType, resourceId, action, ctx))
                    .retrieve()
                    .body(PermissionCheckResponse.class);
            if (response == null) {
                checkUnavailable.increment();
                return Access.unavailable("权限服务不可用");
            }
            if (response.allowed()) {
                checkAllowed.increment();
                return Access.ALLOWED;
            }
            checkDenied.increment();
            return new Access(false, response.reason());
        } catch (RuntimeException error) {
            checkUnavailable.increment();
            log.warn("auth permission check unavailable ({} {} {} {}): {}",
                    username, resourceType, resourceId, action, error.getMessage());
            return Access.unavailable("权限服务不可用");
        } finally {
            sample.stop(checkLatency);
        }
    }

    private Map<String, Object> checkRequestBody(String username, String resourceType,
                                                  Long resourceId, String action,
                                                  Map<String, String> ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("resourceType", resourceType);
        body.put("resourceId", resourceId);
        body.put("action", action);
        body.put("ctx", ctx == null ? Map.of() : ctx);
        return body;
    }

    /**
     * 列表可见性判定（与 auth-service VisibilityResponse 对齐）。
     * mode ∈ ALL / IDS / OPAQUE；IDS 时 ids 为根资源（业务域）id 列表。
     */
    public Visibility visibility(String username, String resourceType, String action) {
        try {
            VisibilityResponse response = restClient.post()
                    .uri("/internal/permissions/visibility")
                    .body(Map.of(
                            "username", username,
                            "resourceType", resourceType,
                            "action", action))
                    .retrieve()
                    .body(VisibilityResponse.class);
            if (response == null) return new Visibility("OPAQUE", List.of());
            return new Visibility(response.mode() == null ? "OPAQUE" : response.mode(),
                    response.ids() == null ? List.of() : response.ids());
        } catch (RuntimeException error) {
            log.warn("auth visibility unavailable ({} {} {}): {}",
                    username, resourceType, action, error.getMessage());
            // 不可用时按"仅自己明确可见的范围"处理会让列表为空（时钟上比放行安全），
            // 但也可能掩盖真实授权；选择 OPAQUE 由调用方逐条 /check 兜底。
            return new Visibility("OPAQUE", List.of());
        }
    }

    public java.util.Set<Long> checkBatch(String username, String resourceType, String action,
                                          Map<Long, Map<String, String>> resourceContexts) {
        if (resourceContexts == null || resourceContexts.isEmpty()) return java.util.Set.of();
        batchRequests.increment();
        batchItems.increment(resourceContexts.size());
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            BatchResponse response = restClient.post().uri("/internal/permissions/check-batch")
                    .body(Map.of("username", username, "resourceType", resourceType,
                            "action", action, "items", resourceContexts.entrySet().stream()
                                    .map(item -> Map.of("resourceId", item.getKey(), "ctx",
                                            item.getValue() == null ? Map.of() : item.getValue()))
                                    .toList()))
                    .retrieve().body(BatchResponse.class);
            if (response == null || response.allowedIds() == null) {
                batchUnavailable.increment();
                return java.util.Set.of();
            }
            batchAllowedIds.increment(response.allowedIds().size());
            return java.util.Set.copyOf(response.allowedIds());
        } catch (RuntimeException error) {
            batchUnavailable.increment();
            log.warn("auth batch permission check unavailable: {}", error.getMessage());
            return java.util.Set.of();
        } finally {
            sample.stop(batchLatency);
        }
    }

    /** 单条判定结果。 */
    public record Access(boolean allowed, String reason, boolean available) {
        public Access(boolean allowed, String reason) { this(allowed, reason, true); }
        public static final Access ALLOWED = new Access(true, null);
        public static Access unavailable(String reason) { return new Access(false, reason, false); }
    }

    /** 列表可见性结果。 */
    public record Visibility(String mode, List<Long> ids) {
    }

    /** 与 auth-service PermissionCheckResponse 对齐。 */
    public record PermissionCheckResponse(boolean allowed, String reason) {
    }

    /** 与 auth-service VisibilityResponse 对齐。 */
    public record VisibilityResponse(String mode, List<Long> ids) {
    }
    public record BatchResponse(List<Long> allowedIds) { }
}
