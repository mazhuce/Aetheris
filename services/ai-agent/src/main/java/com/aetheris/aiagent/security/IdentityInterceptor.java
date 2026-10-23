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


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(request.getAttribute(com.aetheris.security.servlet.TrustedUser.ATTRIBUTE) instanceof com.aetheris.security.servlet.TrustedUser))
            return reject(response,401,"缺少有效认证身份");
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
