package com.aetheris.gateway.config;

import com.aetheris.security.protocol.BrowserSessionCookies;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
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
    @Test void logoutReachesAuthWithIndependentProofWhenRedisIsUnavailable() {
        var store = org.mockito.Mockito.mock(TokenRevocationStore.class);
        org.mockito.Mockito.when(store.revoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new IllegalStateException("redis fixture unavailable")));
        var gate = org.mockito.Mockito.mock(PermissionGate.class);
        var request = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                .cookie(ResponseCookie.from(BrowserSessionCookies.SELECTOR, COOKIE_SID).build())
                .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, COOKIE_SID), token("alice", "viewer", 42L)).build())
                .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, COOKIE_SID), logoutToken(COOKIE_SID)).build())
                .header("X-Aetheris-Identity", "forged").build());
        var forwarded = new AtomicReference<ServerWebExchange>();
        new JwtAuthFilter(KEY, store, gate, GatewayTestKeys.identitySigner()).filter(request, next -> {
            forwarded.set(next); return next.getResponse().setComplete();
        }).block();
        assertNotNull(forwarded.get());
        assertNotNull(forwarded.get().getRequest().getCookies().getFirst(BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, COOKIE_SID)));
        assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("X-Aetheris-Identity"));
        org.mockito.Mockito.verifyNoInteractions(gate);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).isRevoked(org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verify(store).revoke(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test void olderFamilyLogoutCannotBlacklistAccessFromANewerFamily() {
        String access = token("alice", "viewer", 42);
        String olderSid = java.util.UUID.randomUUID().toString();
        var filter = filter(neverCalledGate());
        var logout = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                .cookie(ResponseCookie.from(BrowserSessionCookies.SELECTOR, COOKIE_SID).build())
                .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, COOKIE_SID), access).build())
                .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, olderSid), logoutToken(olderSid)).build()).build());
        filter.filter(logout, next -> next.getResponse().setComplete()).block();
        var forwarded = new AtomicReference<ServerWebExchange>();
        filter.filter(exchange("GET", "/api/metadata/tables", access), next -> {
            forwarded.set(next); return next.getResponse().setComplete();
        }).block();
        assertNotNull(forwarded.get());
    }

    @Test void legacyAccessCannotBypassFamilyCheckEvenWhenAccountIsActive() {
        String legacy = Jwts.builder().subject("alice").claim("uid", 42L).claim("av", 0).claim("typ", "access")
                .expiration(new Date(System.currentTimeMillis() + 60_000)).signWith(KEY).compact();
        var request = exchange("GET", "/api/metadata/tables", legacy);
        new JwtAuthFilter(KEY, new TokenRevocationStore(), neverCalledGate(), GatewayTestKeys.identitySigner())
                .filter(request, ignored -> { throw new AssertionError("Legacy token admitted"); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, request.getResponse().getStatusCode());
    }

    @Test void nonAuthDownstreamNeverReceivesBearerOrCookies() {
        for (String mode : java.util.List.of("user", "pure-key", "preflight")) {
            var builder = mode.equals("preflight") ? MockServerHttpRequest.options("/api/system/configs")
                    : mode.equals("pure-key") ? MockServerHttpRequest.post("/api/serving/v1/apis/ORDER_TOTAL/query")
                    : MockServerHttpRequest.get("/api/metadata/tables");
            var exchange = MockServerWebExchange.from(builder.header("Authorization", "Bearer " + token("alice", "viewer", 42L))
                    .header("Cookie", "aetheris_access=global-secret; aetheris_refresh=refresh-secret")
                    .cookie(new org.springframework.http.HttpCookie("aetheris_access", "global-secret"))
                    .cookie(new org.springframework.http.HttpCookie("aetheris_refresh", "refresh-secret"))
                    .header("X-API-Key", "fixture-key").build());
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
            filter(neverCalledGate()).filter(exchange, next -> { forwarded.set(next); return next.getResponse().setComplete(); }).block();
            assertNotNull(forwarded.get(), mode);
            assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("Authorization"), mode);
            assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("Cookie"), mode);
            assertEquals(0, forwarded.get().getRequest().getCookies().size(), mode);
            assertEquals(null, exchange.getResponse().getStatusCode(), mode);
        }
    }
    @Test
    void publicAndPureKeyEntriesDiscardEveryClientSuppliedProof() {
        for (String path : java.util.List.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                "/api/serving/v1/apis/ORDER_TOTAL/query")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                    .header("X-API-Key", "presented-key")
                    .header("X-Aetheris-User-Proof", "client-user")
                    .header("X-Aetheris-Workload-Proof", "client-workload")
                    .header("X-Aetheris-Response-Proof", "client-response")
                    .header("X-Aetheris-Identity", "client-legacy")
                    .header("X-User-Name", "admin").build());
            filter(neverCalledGate()).filter(exchange, next -> {
                for (String header : java.util.List.of("X-Aetheris-User-Proof", "X-Aetheris-Workload-Proof",
                        "X-Aetheris-Response-Proof", "X-Aetheris-Identity", "X-User-Name")) {
                    assertEquals(null, next.getRequest().getHeaders().getFirst(header), path + " " + header);
                }
                return next.getResponse().setComplete();
            }).block();
        }
    }
    @Test
    void rejectsAmbiguousPathsBeforeAuthenticationOrPublicRouteSelection() {
        for (String path : java.util.List.of("/api/auth/users;ignored", "/api//auth/users",
                "/api/metadata/../auth/users", "/api/./auth/users", "/api/auth%2fusers",
                "/api/auth/users%3bignored", "/api/auth/%252e%252e/users")) {
            var request = MockServerHttpRequest.method(org.springframework.http.HttpMethod.GET, java.net.URI.create(path)).build();
            var exchange = MockServerWebExchange.from(request);
            filter(neverCalledGate()).filter(exchange, ignored -> {
                throw new AssertionError("ambiguous paths must not reach routing");
            }).block();
            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode(), path);
        }
    }

    @Test
    void rejectsInactiveOrRevokedAccountAndFailsClosedWhenAccountCheckIsDown() {
        for (boolean unavailable : java.util.List.of(false, true)) {
            PermissionGate gate = new PermissionGate() {
                public Mono<Boolean> mayManage(String name) { throw new AssertionError(); }
                public Mono<Boolean> mayMutate(String name, String service) { throw new AssertionError(); }
                public Mono<Boolean> browserSessionActive(Long uid, String name, long version, String sid) {
                    assertEquals(42L, uid); assertEquals("alice", name); assertEquals(0L, version);
                    return unavailable ? Mono.error(new IllegalStateException("auth down")) : Mono.just(false);
                }
            };
            var exchange = exchange("GET", "/api/metadata/tables", token("alice", "viewer", 42L));
            filter(gate).filter(exchange, ignored -> {
                throw new AssertionError("inactive account cannot reach downstream");
            }).block();
            assertEquals(unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED,
                    exchange.getResponse().getStatusCode());
        }
    }

    @Test
    void forwardsAccessVersionToLiveAccountCheck() {
        String access = Jwts.builder().subject("alice").claim("uid", 42L).claim("typ", "access").claim("sid", "5628a5d4-778f-4f75-83d2-416c1cf2a269")
                .claim("av", 7L).expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY).compact();
        AtomicReference<Long> observed = new AtomicReference<>();
        PermissionGate gate = new PermissionGate() {
            public Mono<Boolean> mayManage(String name) { return Mono.just(false); }
            public Mono<Boolean> mayMutate(String name, String service) { return Mono.just(false); }
            public Mono<Boolean> browserSessionActive(Long uid, String name, long version, String sid) {
                observed.set(version); return Mono.just(true);
            }
        };
        var exchange = exchange("GET", "/api/metadata/tables", access);
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter(gate).filter(exchange, next -> {forwarded.set(next);return next.getResponse().setComplete();}).block();
        assertEquals(7L, observed.get());
        assertNotNull(forwarded.get());
        var proof = GatewayTestKeys.user(forwarded.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER), "metadata");
        assertEquals(7L, proof.accessVersion());assertEquals(42L, proof.uid());assertEquals("alice", proof.username());
    }
    @Test void signsOnlyTheServiceSelectedByTheActualRouteAndKeepsOneRequestId() {
        for (var pair : java.util.Map.ofEntries(java.util.Map.entry("auth", "/api/auth/profile"),
                java.util.Map.entry("system", "/api/system/activity"),
                java.util.Map.entry("metadata", "/api/metadata/tables"), java.util.Map.entry("quality", "/api/quality/rules"),
                java.util.Map.entry("ingestion", "/api/ingestion/tasks"),
                java.util.Map.entry("governance", "/api/governance/policies"), java.util.Map.entry("workflow", "/api/workflow/tasks"),
                java.util.Map.entry("serving", "/api/analytics/datasets"),
                java.util.Map.entry("ai-agent", "/api/ai/chat/conversations"),
                java.util.Map.entry("learning", "/api/learning/paths"),
                java.util.Map.entry("mcp", "/api/mcp")).entrySet()) {
            var exchange = exchange("GET", pair.getValue(), token("alice", "viewer", 42L));
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
            filter(neverCalledGate()).filter(exchange, next -> {forwarded.set(next);return next.getResponse().setComplete();}).block();
            assertNotNull(forwarded.get(), pair.getKey());
            String proof = forwarded.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER);
            var user = GatewayTestKeys.user(proof, pair.getKey());
            assertEquals(forwarded.get().getRequest().getHeaders().getFirst("X-Request-Id"), user.requestId());
            assertEquals(exchange.getResponse().getHeaders().getFirst("X-Request-Id"), user.requestId());
            assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("X-Aetheris-Identity"));
            assertThrows(com.aetheris.security.protocol.ProtocolException.class, () -> GatewayTestKeys.user(proof, "gateway"));
        }
    }

    @Test
    void publishedApiKeyPostStripsAllClientIdentityHeadersWithoutJwt() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/api/serving/v1/apis/ORDER_TOTAL/query")
                .header("X-API-Key", "presented-key")
                .header("X-Aetheris-Identity", "forged")
                .header("X-User-Name", "admin").header("X-User-Id", "1")
                .header("X-User-Role", "admin").header("X-Internal-Token", "forged")
                .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
        filter(neverCalledGate()).filter(exchange, next -> {
            forwarded.set(next); return next.getResponse().setComplete();
        }).block();
        assertNotNull(forwarded.get());
        for (String header : java.util.List.of("X-Aetheris-Identity", "X-User-Name",
                "X-User-Id", "X-User-Role", "X-Internal-Token")) {
            assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst(header));
        }
        assertEquals("presented-key", forwarded.get().getRequest().getHeaders().getFirst("X-API-Key"));
    }

    @Test
    void apiKeyDoesNotMakeNeighborPathsOrMethodsPublic() {
        for (String path : java.util.List.of("/api/serving/v1/apis/ORDER_TOTAL/query/",
                "/api/serving/v1/apis/ORDER_TOTAL/query/extra", "/api/serving/v1/apis",
                "/api/serving/v1/apis/ORDER_TOTAL/publish", "/api/serving/v1/apis/A/query")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                    .header("X-API-Key", "key").build());
            filter(neverCalledGate()).filter(exchange, ignored -> {
                throw new AssertionError("neighbor route must not bypass JWT");
            }).block();
            assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode(), path);
        }
        var exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/serving/v1/apis/ORDER_TOTAL/query").header("X-API-Key", "key").build());
        filter(neverCalledGate()).filter(exchange, ignored -> {
            throw new AssertionError("GET must not bypass JWT");
        }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    private static final String SECRET = "aetheris-test-secret-with-32-bytes";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private static final String COOKIE_SID = "5628a5d4-778f-4f75-83d2-416c1cf2a269";
    private static final String INTERNAL_TOKEN = "internal-test-token-with-at-least-32-bytes";

    /** 决策门桩：admin 放行、其余拒绝；被探测调用时可选择抛错。 */
    private PermissionGate adminOnlyGate() {
        return new PermissionGate() { public Mono<Boolean> browserSessionActive(Long uid, String username, long version, String sid) { return Mono.just(true); }
            @Override
            public Mono<Boolean> mayMutate(String username, String service) {
                return Mono.just("admin".equals(GatewayTestKeys.user(username, "gateway").username()));
            }

            @Override
            public Mono<Boolean> mayManage(String username) {
                return Mono.just("admin".equals(GatewayTestKeys.user(username, "gateway").username()));
            }
        };
    }

    private PermissionGate failingGate() {
        return new PermissionGate() { public Mono<Boolean> browserSessionActive(Long uid, String username, long version, String sid) { return Mono.just(true); }
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
        return new PermissionGate() { public Mono<Boolean> browserSessionActive(Long uid, String username, long version, String sid) { return Mono.just(true); }
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
                GatewayTestKeys.identitySigner());
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

        assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("X-User-Name"));
        assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("X-User-Id"));
        String identity = forwarded.get().getRequest().getHeaders()
                .getFirst(IdentitySigner.IDENTITY_HEADER);
        assertNotNull(identity, "转发请求必须携带身份签名");
        var proof = GatewayTestKeys.user(identity, "system");
        assertEquals(42L, proof.uid()); assertEquals("alice", proof.username());
        assertEquals(0L, proof.accessVersion());
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
        PermissionGate mutateOnly = new PermissionGate() { public Mono<Boolean> browserSessionActive(Long uid, String username, long version, String sid) { return Mono.just(true); }
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
    void rejectsRefreshTokenUsedAsAccessBearer() {
        String refresh = Jwts.builder()
                .subject("alice")
                .claim("uid", 42L)
                .claim("sv", 0L)
                .claim("typ", "refresh")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY)
                .compact();
        MockServerWebExchange exchange = exchange("GET", "/api/quality/rules", refresh);

        filter(neverCalledGate()).filter(exchange, ignored -> {
            throw new AssertionError("refresh token must not reach downstream");
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

        assertEquals(null, forwarded.get().getRequest().getHeaders().getFirst("X-User-Role"));
        assertEquals("admin", GatewayTestKeys.user(forwarded.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER), "quality").username());
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
            var proof = GatewayTestKeys.user(forwarded.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER), "metadata");
            assertEquals(42L, proof.uid()); assertEquals("alice", proof.username());
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
    void apiAssetLifecycleUsesDataServiceGateAndRetainsMetadataAudience() {
        for(String[] entry:new String[][]{{"POST","/api/metadata/api-assets"},{"PUT","/api/metadata/api-assets/11"},
                {"DELETE","/api/metadata/api-assets/11"},{"POST","/api/metadata/api-assets/11/publish"},{"POST","/api/metadata/api-assets/11/deprecate"}}){
            AtomicReference<String> asked=new AtomicReference<>();AtomicReference<ServerWebExchange> forwarded=new AtomicReference<>();
            PermissionGate gate=new PermissionGate(){
                public Mono<Boolean> browserSessionActive(Long uid,String name,long av, String sid){return Mono.just(uid==42&&name.equals("alice"));}
                public Mono<Boolean> mayMutate(String proof,String service){asked.set(service);return Mono.just("serving".equals(service));}
                public Mono<Boolean> mayManage(String proof){return Mono.just(false);}
            };
            filter(gate).filter(exchange(entry[0],entry[1],token("alice","viewer",42L)),request->{forwarded.set(request);return request.getResponse().setComplete();}).block();
            assertEquals("serving",asked.get());assertNotNull(forwarded.get());
            var user=GatewayTestKeys.user(forwarded.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER),"metadata");assertEquals(42,user.uid());
        }
        AtomicReference<String> asked=new AtomicReference<>();PermissionGate gate=new PermissionGate(){
            public Mono<Boolean> browserSessionActive(Long uid,String name,long av, String sid){return Mono.just(uid==42&&name.equals("alice"));}
            public Mono<Boolean> mayMutate(String proof,String service){asked.set(service);return Mono.just(false);}
            public Mono<Boolean> mayManage(String proof){return Mono.just(false);}
        };
        var neighbor=exchange("POST","/api/metadata/api-assets-extra",token("alice","viewer",42L));
        filter(gate).filter(neighbor,request->{throw new AssertionError("Neighbor cannot pass");}).block();assertEquals("metadata",asked.get());assertEquals(HttpStatus.FORBIDDEN,neighbor.getResponse().getStatusCode());
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
        MockServerWebExchange logout = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .cookie(ResponseCookie.from(BrowserSessionCookies.SELECTOR, COOKIE_SID).build())
                .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, COOKIE_SID), logoutToken(COOKIE_SID)).build()).build());
        filter.filter(logout, filtered -> filtered.getResponse().setComplete()).block();

        MockServerWebExchange subsequent = exchange("GET", "/api/quality/rules", token);
        filter.filter(subsequent, ignored -> {
            throw new AssertionError("revoked token must not reach downstream");
        }).block();

        assertEquals(HttpStatus.UNAUTHORIZED, subsequent.getResponse().getStatusCode());
    }

    @Test
    void acceptsAccessCookieAndLogoutRevokesIt() {
        String token = token("alice", "viewer", 42L);
        JwtAuthFilter filter = filter(neverCalledGate());
        MockServerWebExchange admitted = MockServerWebExchange.from(MockServerHttpRequest.get("/api/quality/rules")
                .cookie(ResponseCookie.from(BrowserSessionCookies.SELECTOR, COOKIE_SID).build())
                .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, COOKIE_SID), token).build()).build());
        var accepted = new AtomicReference<ServerWebExchange>();
        filter.filter(admitted, next -> { accepted.set(next); return next.getResponse().setComplete(); }).block();
        assertNotNull(accepted.get(), "规范 selector 和 family access 必须在登出前真正通过认证");
        var user = GatewayTestKeys.user(accepted.get().getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER), "quality");
        assertEquals(42L, user.uid());
        assertEquals("alice", user.username());
        MockServerWebExchange logout = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/auth/logout")
                        .cookie(ResponseCookie.from(BrowserSessionCookies.SELECTOR, COOKIE_SID).build())
                        .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, COOKIE_SID), logoutToken(COOKIE_SID)).build())
                        .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, COOKIE_SID), token).build()).build());
        filter.filter(logout, forwarded -> forwarded.getResponse().setComplete()).block();

        MockServerWebExchange subsequent = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/quality/rules")
                        .cookie(ResponseCookie.from(BrowserSessionCookies.SELECTOR, COOKIE_SID).build())
                        .cookie(ResponseCookie.from(BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, COOKIE_SID), token).build()).build());
        filter.filter(subsequent, ignored -> {
            throw new AssertionError("revoked cookie must not reach downstream");
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
                .claim("typ", "access")
                .claim("av", 0L)
                .claim("sid", COOKIE_SID)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY)
                .compact();
    }
    private String logoutToken(String sid) {
        return Jwts.builder().subject("alice").claim("uid", 42L).claim("av", 0L).claim("sid", sid)
                .claim("typ", "browser-logout").expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(KEY).compact();
    }
}
