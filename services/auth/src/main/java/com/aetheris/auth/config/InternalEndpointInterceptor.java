package com.aetheris.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * /internal/** 端点的共享令牌校验：服务间调用必须携带 X-Internal-Token
 * 且与 AETHERIS_INTERNAL_TOKEN 一致，否则拒绝。
 *
 * <p>未配置令牌时返回 503 —— 内部端点默认关闭（fail-closed），避免误把
 * 权限判定接口暴露成匿名可调。
 */
@Component
public class InternalEndpointInterceptor implements HandlerInterceptor {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final String token;

    public InternalEndpointInterceptor(@Value("${aetheris.internal-token:}") String token) {
        this.token = token;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws IOException {
        if (token == null || token.isBlank()) {
            writeJson(response, 503, "auth-service 未配置内部令牌（AETHERIS_INTERNAL_TOKEN）");
            return false;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || !secureEquals(token, provided)) {
            writeJson(response, 401, "内部端点鉴权失败");
            return false;
        }
        return true;
    }

    private static boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeJson(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}