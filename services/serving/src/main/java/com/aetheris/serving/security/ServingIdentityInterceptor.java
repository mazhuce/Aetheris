package com.aetheris.serving.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import com.aetheris.security.servlet.TrustedUser;

import java.io.IOException;

/**
 * Serving API 的统一身份入口。发布 API 可以选择使用已签发的 API key，
 * 其他 API 必须携带 Gateway 的 X-Aetheris-Identity 签名。
 */
@Component
public class ServingIdentityInterceptor implements HandlerInterceptor {
    public ServingIdentityInterceptor() {}
    @Deprecated public ServingIdentityInterceptor(IdentityVerifier ignored) {}

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (isApiKeyRoute(request) && hasApiKey(request)) return true;
        if (!(request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser)) {
            response.setStatus(401);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"缺少有效的用户身份\"}");
            return false;
        }
        return true;
    }

    private static boolean hasApiKey(HttpServletRequest request) {
        String value = request.getHeader("X-API-Key");
        return value != null && !value.isBlank();
    }

    public static boolean apiKeyRoute(String method,String path) {
        return "POST".equals(method) && path != null
                && path.matches("^/api/serving/v1/apis/[A-Za-z][A-Za-z0-9_]{1,63}/query$");
    }
    private static boolean isApiKeyRoute(HttpServletRequest request){return apiKeyRoute(request.getMethod(),request.getRequestURI());}
}
