package com.aetheris.metadata.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * /api/metadata/** 的强制身份入口：验签网关身份签名并缓存，无效身份直接
 * 401 终止请求。资源级判定仍由各控制器通过 {@link MetadataSecurity} 执行，
 * 这里只保证"metadata 业务端点不接受匿名请求"（fail-closed）。
 */
@Component
public class IdentityInterceptor implements HandlerInterceptor {

    private final IdentityVerifier verifier;

    public IdentityInterceptor(IdentityVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!verifier.isConfigured()) {
            writeJson(response, 503, "metadata-service 未配置内部令牌（AETHERIS_INTERNAL_TOKEN）");
            return false;
        }
        AetherisIdentity identity = verifier.verify(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            writeJson(response, 401, "缺少有效的网关身份签名");
            return false;
        }
        request.setAttribute(MetadataSecurity.IDENTITY_ATTRIBUTE, identity);
        return true;
    }

    private static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}