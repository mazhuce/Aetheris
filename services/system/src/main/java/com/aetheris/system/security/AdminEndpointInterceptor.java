package com.aetheris.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Management guard: only a v2 TrustedUser plus a live signed auth decision is accepted. */
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