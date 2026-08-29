package com.aetheris.gateway.audit;

import com.aetheris.gateway.config.JwtAuthFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuditLogFilter implements GlobalFilter, Ordered {

    private final AuditLogClient auditLogClient;

    public AuditLogFilter(AuditLogClient auditLogClient) {
        this.auditLogClient = auditLogClient;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        String path = exchange.getRequest().getURI().getPath();
        if (!shouldAudit(method, path)) {
            return chain.filter(exchange);
        }

        long startedAt = System.currentTimeMillis();
        return chain.filter(exchange).then(Mono.defer(() -> {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            AuditEvent event = new AuditEvent(
                    attribute(exchange, JwtAuthFilter.AUTHENTICATED_USER_ATTRIBUTE),
                    attribute(exchange, JwtAuthFilter.AUTHENTICATED_ROLE_ATTRIBUTE),
                    method.name(),
                    path,
                    status == null ? 200 : status.value(),
                    System.currentTimeMillis() - startedAt,
                    clientIp(exchange));
            return auditLogClient.record(event);
        }));
    }

    private boolean shouldAudit(HttpMethod method, String path) {
        if (!path.startsWith("/api/") || method == HttpMethod.OPTIONS) {
            return false;
        }
        // 登录必须审计，否则爆破与失败登录无痕迹。
        if (path.startsWith("/api/auth/login")) {
            return true;
        }
        return method != HttpMethod.GET && method != HttpMethod.HEAD;
    }

    /** 只信任 JwtAuthFilter 写入的属性；请求头可被客户端伪造。 */
    private String attribute(ServerWebExchange exchange, String name) {
        Object value = exchange.getAttribute(name);
        return value instanceof String text && !text.isBlank() ? text : "anonymous";
    }

    private String clientIp(ServerWebExchange exchange) {
        if (exchange.getRequest().getRemoteAddress() == null) {
            return null;
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        // 必须早于 JwtAuthFilter(-100)，否则 401/403 被短路后审计拿不到事件。
        return -110;
    }
}
