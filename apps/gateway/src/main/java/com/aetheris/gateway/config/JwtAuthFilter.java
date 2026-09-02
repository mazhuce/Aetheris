package com.aetheris.gateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    /** 已认证身份，供审计等更早的过滤器读取；请求头可被客户端伪造，不能作为审计来源。 */
    public static final String AUTHENTICATED_USER_ATTRIBUTE = "aetheris.auth.username";
    public static final String AUTHENTICATED_ROLE_ATTRIBUTE = "aetheris.auth.role";

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/auth/login",
            "/actuator/health",
            "/actuator/info"
    );
    private static final Set<String> ADMIN_MUTATION_PREFIXES = Set.of(
            "/api/system/",
            "/api/metadata/",
            "/api/quality/",
            "/api/ingestion/",
            "/api/governance/",
            "/api/workflow/"
    );

    private final SecretKey key;
    private final TokenRevocationStore revocationStore;

    @Autowired
    public JwtAuthFilter(@Value("${jwt.secret}") String secret, TokenRevocationStore revocationStore) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.revocationStore = revocationStore;
    }

    public JwtAuthFilter(String secret) {
        this(secret, new TokenRevocationStore());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();

        if (method == HttpMethod.OPTIONS || isPublic(path)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(authorization.substring(7))
                    .getPayload();
            String rawToken = authorization.substring(7);
            String jti = claims.getId();
            String revocationKey = jti == null ? TokenRevocationStore.fingerprint(rawToken) : jti;
            if (revocationStore.isRevoked(revocationKey)) {
                return unauthorized(exchange);
            }
            if ("/api/auth/logout".equals(path)) {
                revocationStore.revoke(revocationKey, claims.getExpiration() == null
                        ? null : claims.getExpiration().toInstant());
            }
            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            if (role == null) {
                role = "viewer";
            }
            String authenticatedRole = role;
            exchange.getAttributes().put(AUTHENTICATED_USER_ATTRIBUTE, username);
            exchange.getAttributes().put(AUTHENTICATED_ROLE_ATTRIBUTE, authenticatedRole);
            if (requiresAdmin(path, method) && !"admin".equals(role)) {
                return forbidden(exchange);
            }

            ServerHttpRequest mutated = exchange.getRequest()
                    .mutate()
                    .headers(headers -> {
                        headers.set("X-User-Name", username);
                        headers.set("X-User-Role", authenticatedRole);
                    })
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            return unauthorized(exchange);
        }
    }

    private boolean isPublic(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean requiresAdmin(String path, HttpMethod method) {
        if (path.startsWith("/api/auth/users") || path.startsWith("/api/system/audit-logs")) {
            return true;
        }
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            return false;
        }
        return ADMIN_MUTATION_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
