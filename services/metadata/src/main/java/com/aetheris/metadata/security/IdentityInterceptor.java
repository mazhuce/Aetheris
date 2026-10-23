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

    private final MetadataSecurity security;

    public IdentityInterceptor(MetadataSecurity security) {
        this.security = security;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        AetherisIdentity identity = security.identity(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            writeJson(response, 401, "缺少有效的用户证明");
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