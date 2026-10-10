package com.aetheris.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Verifies the gateway identity for system read endpoints whose response scope
 * depends on the caller.  The role header is deliberately not used here: role
 * claims are intentionally short-lived state and can outlive a role change.
 */
@Component
public class SystemIdentityInterceptor implements HandlerInterceptor {

    public static final String IDENTITY_ATTRIBUTE = "aetheris.system.identity";

    private final IdentityVerifier verifier;

    public SystemIdentityInterceptor(IdentityVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (!verifier.isConfigured()) {
            return reject(response, 503,
                    "system-service 未配置内部令牌（AETHERIS_INTERNAL_TOKEN）");
        }
        AetherisIdentity identity = verifier.verify(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            return reject(response, 401, "缺少有效的网关身份签名");
        }
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }

    private static boolean reject(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}
