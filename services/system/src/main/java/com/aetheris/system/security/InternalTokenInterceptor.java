package com.aetheris.system.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Protects system's service-to-service write contracts with the shared token. */
@Component
public class InternalTokenInterceptor implements HandlerInterceptor {

    private static final String HEADER = "X-Internal-Token";
    private final byte[] expected;

    public InternalTokenInterceptor(@Value("${aetheris.internal-token:}") String token) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (expected.length == 0) {
            return reject(response, 503,
                    "system-service 未配置内部令牌（AETHERIS_INTERNAL_TOKEN）");
        }
        String provided = request.getHeader(HEADER);
        if (provided == null || !MessageDigest.isEqual(expected,
                provided.getBytes(StandardCharsets.UTF_8))) {
            return reject(response, 401, "缺少有效的服务间令牌");
        }
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
