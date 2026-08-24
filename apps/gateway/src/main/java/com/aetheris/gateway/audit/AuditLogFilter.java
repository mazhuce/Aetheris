package com.aetheris.gateway.audit;

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
                    header(exchange, "X-User-Name", "anonymous"),
                    header(exchange, "X-User-Role", "anonymous"),
                    method.name(),
                    path,
                    status == null ? 200 : status.value(),
                    System.currentTimeMillis() - startedAt,
                    clientIp(exchange));
            return auditLogClient.record(event);
        }));
    }

    private boolean shouldAudit(HttpMethod method, String path) {
        return path.startsWith("/api/")
                && method != HttpMethod.GET
                && method != HttpMethod.HEAD
                && method != HttpMethod.OPTIONS;
    }

    private String header(ServerWebExchange exchange, String name, String fallback) {
        String value = exchange.getRequest().getHeaders().getFirst(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String clientIp(ServerWebExchange exchange) {
        if (exchange.getRequest().getRemoteAddress() == null) {
            return null;
        }
        return exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return -90;
    }
}
