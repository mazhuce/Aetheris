package com.aetheris.aiagent.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** /api/ai 的 fail-closed 身份入口；业务代码只使用验签后的 request attribute。 */
@Component
public class IdentityInterceptor implements HandlerInterceptor {
    public static final String IDENTITY_ATTRIBUTE = "aetheris.ai.identity";

    private final IdentityVerifier verifier;

    public IdentityInterceptor(IdentityVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!verifier.configured()) return reject(response, 503, "ai-agent 未配置内部令牌");
        AetherisIdentity identity = verifier.verify(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            return reject(response, 401, "缺少有效的网关身份签名");
        }
        request.setAttribute(IDENTITY_ATTRIBUTE, identity);
        return true;
    }

    private static boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }
}
