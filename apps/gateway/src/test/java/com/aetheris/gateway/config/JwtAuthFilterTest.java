package com.aetheris.gateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtAuthFilterTest {

    private static final String SECRET = "aetheris-test-secret-with-32-bytes";

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtAuthFilter("too-short"));
    }

    @Test
    void rejectsRequestWithoutToken() {
        JwtAuthFilter filter = new JwtAuthFilter(SECRET);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/system/users").build());

        filter.filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void replacesSpoofedUsernameHeaderWithTokenSubject() {
        String token = token("alice", "viewer");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/system/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Name", "attacker")
                        .header("X-User-Role", "admin")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        new JwtAuthFilter(SECRET).filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange);
            return filteredExchange.getResponse().setComplete();
        }).block();

        assertEquals("alice", forwarded.get().getRequest().getHeaders().getFirst("X-User-Name"));
        assertEquals(1, forwarded.get().getRequest().getHeaders().get("X-User-Name").size());
        assertEquals("viewer", forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals(1, forwarded.get().getRequest().getHeaders().get("X-User-Role").size());
    }

    @Test
    void blocksViewerFromMutatingManagedResources() {
        MockServerWebExchange exchange = exchange("POST", "/api/quality/rules", token("alice", "viewer"));

        new JwtAuthFilter(SECRET).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void blocksViewerFromReadingAuditLogs() {
        MockServerWebExchange exchange = exchange("GET", "/api/system/audit-logs", token("alice", "viewer"));

        new JwtAuthFilter(SECRET).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void allowsAdminToMutateManagedResources() {
        MockServerWebExchange exchange = exchange("POST", "/api/quality/rules", token("admin", "admin"));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        new JwtAuthFilter(SECRET).filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange);
            return filteredExchange.getResponse().setComplete();
        }).block();

        assertEquals("admin", forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
    }

    private MockServerWebExchange exchange(String method, String path, String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
    }

    private String token(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
