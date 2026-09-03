package com.aetheris.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 系统管理敏感端点的服务端权限门（审计日志、权限审计事件）。
 *
 * <p>判定链：身份验签（X-Aetheris-Identity，防止绕过网关直接伪造请求头）
 * → auth 决策引擎 may-manage。内部令牌未配置时 503（fail-closed），
 * 缺签名 401，无系统管理权限 403。与网关的粗粒度拦截是两层独立防线。
 */
@Component
public class AdminEndpointInterceptor implements HandlerInterceptor {

    private final IdentityVerifier identityVerifier;
    private final AuthPermissionClient authPermissionClient;

    public AdminEndpointInterceptor(IdentityVerifier identityVerifier,
                                    AuthPermissionClient authPermissionClient) {
        this.identityVerifier = identityVerifier;
        this.authPermissionClient = authPermissionClient;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (!identityVerifier.isConfigured()) {
            writeJson(response, 503, "system-service 未配置内部令牌（AETHERIS_INTERNAL_TOKEN）");
            return false;
        }
        AetherisIdentity identity = identityVerifier.verify(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            writeJson(response, 401, "缺少有效的网关身份签名");
            return false;
        }
        if (!authPermissionClient.mayManage(identity.username())) {
            writeJson(response, 403, "没有系统管理权限");
            return false;
        }
        return true;
    }

    private static void writeJson(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    }
}