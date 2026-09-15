package com.aetheris.metadata.security;

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

    @Autowired
    public AuthPermissionClient(
            RestClient.Builder builder,
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.auth.base-url:http://localhost:8087}") String authBaseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1500);
        this.restClient = builder
                .requestFactory(factory)
                .baseUrl(authBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    AuthPermissionClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /** 单条资源级权限判定（与 auth-service PermissionCheckResponse 对齐）。 */
    public Access check(String username, String resourceType, Long resourceId,
                        String action, Map<String, String> ctx) {
        try {
            PermissionCheckResponse response = restClient.post()
                    .uri("/internal/permissions/check")
                    .body(checkRequestBody(username, resourceType, resourceId, action, ctx))
                    .retrieve()
                    .body(PermissionCheckResponse.class);
            return response != null && response.allowed()
                    ? Access.ALLOWED
                    : new Access(false, response == null ? null : response.reason());
        } catch (RuntimeException error) {
            log.warn("auth permission check unavailable ({} {} {} {}): {}",
                    username, resourceType, resourceId, action, error.getMessage());
            return new Access(false, "权限服务不可用");
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
        try {
            BatchResponse response = restClient.post().uri("/internal/permissions/check-batch")
                    .body(Map.of("username", username, "resourceType", resourceType,
                            "action", action, "items", resourceContexts.entrySet().stream()
                                    .map(item -> Map.of("resourceId", item.getKey(), "ctx",
                                            item.getValue() == null ? Map.of() : item.getValue()))
                                    .toList()))
                    .retrieve().body(BatchResponse.class);
            return response == null || response.allowedIds() == null ? java.util.Set.of()
                    : java.util.Set.copyOf(response.allowedIds());
        } catch (RuntimeException error) {
            log.warn("auth batch permission check unavailable: {}", error.getMessage());
            return java.util.Set.of();
        }
    }

    /** 单条判定结果。 */
    public record Access(boolean allowed, String reason) {
        public static final Access ALLOWED = new Access(true, null);
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
