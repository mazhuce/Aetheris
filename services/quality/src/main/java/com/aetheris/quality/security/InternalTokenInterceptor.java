package com.aetheris.quality.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protects service-only quality endpoints from direct network callers. */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {
    private final byte[] expected;

    public InternalTokenInterceptor(@Value("${aetheris.internal-token:}") String token) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        byte[] actual = bytes(request.getHeader("X-Internal-Token"));
        if (expected.length == 0 || !MessageDigest.isEqual(expected, actual)) {
            response.setStatus(expected.length == 0 ? 503 : 401);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"message\":\"缺少有效的内部服务令牌\"}");
            return false;
        }
        return true;
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
