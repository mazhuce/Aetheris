package com.aetheris.mcp.security;

import com.aetheris.mcp.config.McpProperties;
import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把 v2 USER 证明适配为 MCP 调用者。生产路径不接受任何自报身份；
 * dev-mode 必须显式开启，且只能用于单机演示。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
public class McpIdentityFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpIdentityFilter.class);
    private final McpProperties properties;

    public McpIdentityFilter(McpProperties properties) {
        this.properties = properties;
        if (properties.isDevMode()) {
            log.warn("mcp dev-mode is enabled: requests without a verified user proof use the configured demo learner");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api/mcp") || path.startsWith("/api/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getAttribute(CurrentUser.ATTRIBUTE) instanceof CurrentUser) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser trusted) {
            var proof = trusted.proof();
            request.setAttribute(CurrentUser.ATTRIBUTE,
                    new CurrentUser(proof.uid(), proof.username(), trusted.compactProof()));
            chain.doFilter(request, response);
            return;
        }
        if (properties.isDevMode()) {
            String demo = request.getHeader("X-Aetheris-Dev-User");
            String username = demo == null || demo.isBlank() ? properties.getDevUser() : demo.trim();
            if (!username.matches("[A-Za-z0-9._-]{1,64}")) {
                reject(response, 400, "非法的演示用户");
                return;
            }
            request.setAttribute(CurrentUser.ATTRIBUTE,
                    new CurrentUser("dev-admin".equals(username) ? 9000L : 9001L, username, null));
            chain.doFilter(request, response);
            return;
        }
        reject(response, 401, "缺少有效的用户身份");
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        if (response.isCommitted()) throw new IllegalStateException("响应已提交，无法拒绝");
        byte[] body = ("{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.reset();
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
