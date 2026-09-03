package com.aetheris.system.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 调用 auth-service 内部权限端点的客户端：判定输入是经过验签的身份，
 * 判定逻辑由 auth-service 决策引擎维护（角色/授权/组），业务服务不做本地复制。
 */
@Component
public class AuthPermissionClient {

    private static final Logger log = LoggerFactory.getLogger(AuthPermissionClient.class);

    private final RestClient restClient;

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

    /** 某个用户名是否具备系统管理权限。auth 不可用时返回 false（fail-closed）。 */
    public boolean mayManage(String username) {
        try {
            PermissionCheckResponse response = restClient.post()
                    .uri("/internal/permissions/may-manage")
                    .body(Map.of("username", username))
                    .retrieve()
                    .body(PermissionCheckResponse.class);
            return response != null && response.allowed();
        } catch (RuntimeException error) {
            log.warn("auth permission check unavailable: {}", error.getMessage());
            return false;
        }
    }

    /** 与 auth-service PermissionCheckResponse 对齐。 */
    public record PermissionCheckResponse(boolean allowed, String reason) {
    }
}