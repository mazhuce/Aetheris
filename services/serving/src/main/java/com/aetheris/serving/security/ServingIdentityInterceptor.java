package com.aetheris.serving.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * Serving API 的统一身份入口。发布 API 可以选择使用已签发的 API key，
 * 其他 API 必须携带 Gateway 的 X-Aetheris-Identity 签名。
 */
@Component
public class ServingIdentityInterceptor implements HandlerInterceptor {
    private final IdentityVerifier verifier;

    public ServingIdentityInterceptor(IdentityVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (isApiKeyRoute(request) && hasApiKey(request)) return true;
        if (!verifier.configured()) {
            response.sendError(503, "serving-service 未配置内部令牌");
            return false;
        }
        ServingIdentity identity = verifier.verify(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            response.sendError(401, "缺少有效的网关身份签名");
            return false;
        }
        request.setAttribute(ServingSecurity.ATTRIBUTE, identity);
        return true;
    }

    private static boolean hasApiKey(HttpServletRequest request) {
        String value = request.getHeader("X-API-Key");
        return value != null && !value.isBlank();
    }

    private static boolean isApiKeyRoute(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/serving/v1/apis".equals(path) || path.startsWith("/api/serving/v1/apis/");
    }
}
