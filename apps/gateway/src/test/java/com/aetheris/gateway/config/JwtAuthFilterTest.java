package com.aetheris.gateway.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtAuthFilterTest {

    private static final String SECRET = "aetheris-test-secret-with-32-bytes";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final String INTERNAL_TOKEN = "internal-test-token";

    /** 决策门桩：admin 放行、其余拒绝；被探测调用时可选择抛错。 */
    private PermissionGate adminOnlyGate() {
        return new PermissionGate() {
            @Override
            public Mono<Boolean> mayMutate(String username, String service) {
                return Mono.just("admin".equals(username));
            }

            @Override
            public Mono<Boolean> mayManage(String username) {
                return Mono.just("admin".equals(username));
            }
        };
    }

    private PermissionGate failingGate() {
        return new PermissionGate() {
            @Override
            public Mono<Boolean> mayMutate(String username, String service) {
                return Mono.error(new IllegalStateException("auth down"));
            }

            @Override
            public Mono<Boolean> mayManage(String username) {
                return Mono.error(new IllegalStateException("auth down"));
            }
        };
    }

    private PermissionGate neverCalledGate() {
        return new PermissionGate() {
            @Override
            public Mono<Boolean> mayMutate(String username, String service) {
                throw new AssertionError("mayMutate must not be consulted");
            }

            @Override
            public Mono<Boolean> mayManage(String username) {
                throw new AssertionError("mayManage must not be consulted");
            }
        };
    }

    private static JwtAuthFilter filter(PermissionGate gate) {
        return new JwtAuthFilter(KEY, new TokenRevocationStore(), gate,
                new IdentitySigner(INTERNAL_TOKEN));
    }

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> JwtAuthFilter.parseKey("too-short"));
    }

    @Test
    void rejectsRequestWithoutToken() {
        JwtAuthFilter filter = filter(neverCalledGate());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/system/users").build());

        filter.filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void doesNotTreatLookalikeLoginPathAsPublic() {
        JwtAuthFilter filter = filter(neverCalledGate());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/login-evil").build());

        filter.filter(exchange, ignored -> {
            throw new AssertionError("lookalike login path must not be public");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void replacesSpoofedHeadersWithTokenIdentityAndSignsIt() {
        String token = token("alice", "viewer", 42L);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/system/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-User-Name", "attacker")
                        .header("X-User-Role", "admin")
                        .header("X-User-Id", "999")
                        .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter(neverCalledGate()).filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange);
            return filteredExchange.getResponse().setComplete();
        }).block();

        assertEquals("alice", forwarded.get().getRequest().getHeaders().getFirst("X-User-Name"));
        assertEquals(1, forwarded.get().getRequest().getHeaders().get("X-User-Name").size());
        assertEquals("viewer", forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("42", forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
        String identity = forwarded.get().getRequest().getHeaders()
                .getFirst(IdentitySigner.IDENTITY_HEADER);
        assertNotNull(identity, "转发请求必须携带身份签名");
        assertArrayEquals(new String[]{"42", "alice"},
                new IdentitySigner(INTERNAL_TOKEN).verify(identity));
    }

    @Test
    void blocksViewerFromMutatingManagedResources() {
        MockServerWebExchange exchange = exchange("POST", "/api/quality/rules", token("alice", "viewer", 42L));

        filter(adminOnlyGate()).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void blocksViewerFromCreatingOrRunningWorkflowTasks() {
        for (String path : new String[]{"/api/workflow/tasks", "/api/workflow/tasks/7/run"}) {
            MockServerWebExchange exchange = exchange("POST", path, token("alice", "viewer", 42L));

            filter(adminOnlyGate()).filter(exchange, ignored -> {
                throw new AssertionError("filter chain must not run");
            }).block();

            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
        }
    }

    @Test
    void blocksViewerFromReadingAuditLogs() {
        MockServerWebExchange exchange = exchange("GET", "/api/system/audit-logs", token("alice", "viewer", 42L));

        filter(adminOnlyGate()).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void blocksViewerFromPermissionEvents() {
        MockServerWebExchange exchange = exchange("GET", "/api/system/permission-events", token("alice", "viewer", 42L));

        filter(adminOnlyGate()).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void sensitiveSystemWritesRequireManagePermissionBeforeServiceMutationGate() {
        PermissionGate mutateOnly = new PermissionGate() {
            @Override
            public Mono<Boolean> mayMutate(String username, String service) {
                return Mono.just(true);
            }

            @Override
            public Mono<Boolean> mayManage(String username) {
                return Mono.just(false);
            }
        };
        MockServerWebExchange exchange = exchange("POST", "/api/system/audit-logs", token("alice", "viewer", 42L));

        filter(mutateOnly).filter(exchange, ignored -> {
            throw new AssertionError("audit writes must not reach downstream without manage permission");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsAuthenticatedTokenWithoutStableUserId() {
        String malformed = Jwts.builder()
                .subject("alice")
                .claim("role", "admin")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY)
                .compact();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/quality/rules")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + malformed)
                        .header("X-User-Id", "1")
                        .build());

        filter(neverCalledGate()).filter(exchange, ignored -> {
            throw new AssertionError("malformed identity must not reach downstream");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsMalformedClaimTypesWithoutThrowing() {
        for (String malformed : new String[]{
                Jwts.builder().subject("alice").claim("uid", "not-a-number")
                        .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact(),
                Jwts.builder().subject("alice").claim("uid", 42L).claim("role", 7)
                        .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact(),
                Jwts.builder().subject("alice").claim("uid", 42L).signWith(KEY).compact()
        }) {
            MockServerWebExchange exchange = exchange("GET", "/api/quality/rules", malformed);
            filter(neverCalledGate()).filter(exchange, ignored -> {
                throw new AssertionError("malformed claims must not reach downstream");
            }).block();
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void blocksViewerFromRbacAdminEndpoints() {
        MockServerWebExchange exchange = exchange("GET", "/api/auth/rbac/grants", token("alice", "viewer", 42L));

        filter(adminOnlyGate()).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    @Test
    void allowsAdminToMutateManagedResources() {
        MockServerWebExchange exchange = exchange("POST", "/api/quality/rules", token("admin", "admin", 1L));
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter(adminOnlyGate()).filter(exchange, filteredExchange -> {
            forwarded.set(filteredExchange);
            return filteredExchange.getResponse().setComplete();
        }).block();

        assertEquals("admin", forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
    }

    @Test
    void readRequestsSkipCoarseGate() {
        MockServerWebExchange exchange = exchange("GET", "/api/quality/rules", token("alice", "viewer", 42L));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter(neverCalledGate()).filter(exchange, filteredExchange -> {
            forwarded.set(true);
            return filteredExchange.getResponse().setComplete();
        }).block();

        assertEquals(Boolean.TRUE, forwarded.get(), "读请求不应被粗粒度入口拦截");
    }

    @Test
    void servingQueryExecutionSkipsCoarseGateForDownstreamCheck() {
        MockServerWebExchange exchange = exchange("POST", "/api/serving/query", token("alice", "viewer", 42L));
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);

        filter(neverCalledGate()).filter(exchange, filteredExchange -> {
            forwarded.set(true);
            return filteredExchange.getResponse().setComplete();
        }).block();

        assertEquals(Boolean.TRUE, forwarded.get(),
                "serving 查询执行由业务服务做资源级判断，网关不做粗粒度拦截");
    }

    @Test
    void datasourceReadPostsReachResourceAuthorizationWithSignedIdentity() {
        for (String path : new String[]{"/api/metadata/query", "/api/metadata/datasources/5/query",
                "/api/metadata/datasources/5/test", "/api/metadata/datasources/5/preview"}) {
            MockServerWebExchange exchange = exchange("POST", path, token("alice", "viewer", 42L));
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
            filter(neverCalledGate()).filter(exchange, request -> {
                forwarded.set(request);
                // The downstream resource check can still deny access.
                request.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return request.getResponse().setComplete();
            }).block();
            assertNotNull(forwarded.get(), path);
            assertArrayEquals(new String[]{"42", "alice"}, new IdentitySigner(INTERNAL_TOKEN).verify(
                    forwarded.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER)));
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
        }
    }

    @Test
    void datasourceReadPostsStillRequireAuthentication() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/metadata/datasources/5/query").build());
        filter(neverCalledGate()).filter(exchange, request -> {
            throw new AssertionError("anonymous query must not be forwarded");
        }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void datasourceMutationAndLookalikeRoutesKeepCoarseGate() {
        for (String path : new String[]{"/api/metadata/datasources", "/api/metadata/datasources/test",
                "/api/metadata/datasources/5/query-extra", "/api/metadata/datasources/5/query/run",
                "/api/metadata/query/export", "/api/metadata/datasources/5/sync"}) {
            MockServerWebExchange exchange = exchange("POST", path, token("alice", "viewer", 42L));
            filter(adminOnlyGate()).filter(exchange, request -> {
                throw new AssertionError("write/lookalike route must not be forwarded: " + path);
            }).block();
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
        }
        MockServerWebExchange update = exchange("PUT", "/api/metadata/datasources/5/query", token("alice", "viewer", 42L));
        filter(adminOnlyGate()).filter(update, request -> {
            throw new AssertionError("non-POST must retain mutation gate");
        }).block();
        assertEquals(HttpStatus.FORBIDDEN, update.getResponse().getStatusCode());
    }

    @Test
    void permissionServiceUnavailableFailsClosed() {
        MockServerWebExchange exchange = exchange("POST", "/api/quality/rules", token("admin", "admin", 1L));

        filter(failingGate()).filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run when auth is unavailable");
        }).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }

    @Test
    void requiresTokenForNonHealthActuatorEndpoints() {
        JwtAuthFilter filter = filter(neverCalledGate());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/gateway/routes").build());

        filter.filter(exchange, ignored -> {
            throw new AssertionError("filter chain must not run");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void exposesAuthenticatedIdentityAsExchangeAttributes() {
        MockServerWebExchange exchange = exchange("GET", "/api/quality/rules", token("alice", "viewer", 42L));

        filter(neverCalledGate()).filter(
                exchange,
                filteredExchange -> filteredExchange.getResponse().setComplete()).block();

        assertEquals("alice", exchange.getAttribute(JwtAuthFilter.AUTHENTICATED_USER_ATTRIBUTE));
        assertEquals("viewer", exchange.getAttribute(JwtAuthFilter.AUTHENTICATED_ROLE_ATTRIBUTE));
        assertEquals(42L, (Long) exchange.getAttribute(JwtAuthFilter.AUTHENTICATED_UID_ATTRIBUTE));
    }

    @Test
    void logoutRevokesTokenForSubsequentRequests() {
        String token = token("alice", "viewer", 42L);
        JwtAuthFilter filter = filter(neverCalledGate());
        MockServerWebExchange logout = exchange("POST", "/api/auth/logout", token);
        filter.filter(logout, filtered -> filtered.getResponse().setComplete()).block();

        MockServerWebExchange subsequent = exchange("GET", "/api/quality/rules", token);
        filter.filter(subsequent, ignored -> {
            throw new AssertionError("revoked token must not reach downstream");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, subsequent.getResponse().getStatusCode());
    }

    private MockServerWebExchange exchange(String method, String path, String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.method(org.springframework.http.HttpMethod.valueOf(method), path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());
    }

    private String token(String username, String role, long uid) {
        return Jwts.builder()
                .subject(username)
                .claim("uid", uid)
                .claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY)
                .compact();
    }
}
