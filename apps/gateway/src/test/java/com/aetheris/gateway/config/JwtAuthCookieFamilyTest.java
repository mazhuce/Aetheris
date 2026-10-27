package com.aetheris.gateway.config;

import com.aetheris.security.protocol.BrowserSessionCookies;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JwtAuthCookieFamilyTest {
    private static final SecretKey KEY = Keys.hmacShaKeyFor(
            "aetheris-test-secret-with-32-bytes".getBytes(StandardCharsets.UTF_8));
    private static final String A = "5628a5d4-778f-4f75-83d2-416c1cf2a269";
    private static final String B = "9ee94d1c-5588-43cf-b3a8-c7510a7ea180";

    @Test
    void mixedOldAndCurrentCookiesSelectOnlyBRegardlessOfCookieOrder() {
        for (boolean oldFirst : List.of(true, false)) {
            var store = mock(TokenRevocationStore.class);
            var gate = mock(PermissionGate.class);
            when(store.isRevoked("access-b")).thenReturn(Mono.just(false));
            when(gate.browserSessionActive(84L, "bob", 7L, B)).thenReturn(Mono.just(true));
            HttpCookie oldAccess = credential(BrowserSessionCookies.ACCESS, A, accessA());
            HttpCookie currentAccess = credential(BrowserSessionCookies.ACCESS, B, accessB());
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                    .cookie(selector(B), oldFirst ? oldAccess : currentAccess, oldFirst ? currentAccess : oldAccess,
                            credential(BrowserSessionCookies.LOGOUT, A, logout("alice", 42L, 2L, A)),
                            new HttpCookie("aetheris_access", accessA()))
                    .header("X-Aetheris-Identity", "forged").build());

            var forwarded = forwarded(exchange, store, gate);
            var proof = GatewayTestKeys.user(forwarded.getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER), "metadata");
            assertEquals(84L, proof.uid());
            assertEquals("bob", proof.username());
            assertEquals(7L, proof.accessVersion());
            assertEquals(84L, (Long) exchange.getAttribute(JwtAuthFilter.AUTHENTICATED_UID_ATTRIBUTE));
            assertTrue(forwarded.getRequest().getCookies().isEmpty());
            assertNull(forwarded.getRequest().getHeaders().getFirst(HttpHeaders.COOKIE));
            assertNull(forwarded.getRequest().getHeaders().getFirst("X-Aetheris-Identity"));
            verify(store).isRevoked("access-b");
            verify(gate).browserSessionActive(84L, "bob", 7L, B);
            verifyNoMoreInteractions(store, gate);
        }
    }

    @Test
    void missingSelectorAndFlatLegacyCredentialsNeverAuthenticate() {
        var family = credential(BrowserSessionCookies.ACCESS, B, accessB());
        var flat = new HttpCookie("aetheris_access", accessB());
        for (HttpCookie[] cookies : List.of(new HttpCookie[] {family}, new HttpCookie[] {flat},
                new HttpCookie[] {selector(B), flat})) {
            rejected(MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables").cookie(cookies).build()));
        }
    }

    @Test
    void duplicateSelectorsAreRejectedEvenWhenBothValuesAreIdentical() {
        for (String second : List.of(A, B)) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                    .cookie(selector(B), selector(second), credential(BrowserSessionCookies.ACCESS, B, accessB())).build());
            assertEquals(2, exchange.getRequest().getCookies().get(BrowserSessionCookies.SELECTOR).size());
            rejected(exchange);
        }
    }

    @Test
    void nonCanonicalSelectorsDoNotFallBackToAnotherCookie() {
        for (String invalid : List.of(B.toUpperCase(Locale.ROOT), "1-1-1-1-1", B + " ", "not-a-uuid", "")) {
            rejected(MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                    .cookie(selector(invalid), credential(BrowserSessionCookies.ACCESS, B, accessB()),
                            new HttpCookie("aetheris_access", accessB())).build()));
        }
    }

    @Test
    void duplicateSelectedAccessCookiesAreRejectedEvenWhenIdentical() {
        String selectedAccess = accessB();
        for (String second : List.of(accessA(), selectedAccess)) {
            String name = BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, B);
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                    .cookie(selector(B), new HttpCookie(name, selectedAccess), new HttpCookie(name, second)).build());
            assertEquals(2, exchange.getRequest().getCookies().get(name).size());
            rejected(exchange);
        }
    }

    @Test
    void cookieNameForBCannotCarryAnAccessJwtForA() {
        rejected(MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                .cookie(selector(B), credential(BrowserSessionCookies.ACCESS, B, accessA())).build()));
    }

    @Test
    void validBearerUsesItsOwnStrictFamilyWithoutDependingOnACookieSelector() {
        for (HttpCookie[] cookies : List.of(new HttpCookie[0], new HttpCookie[] {selector(B)},
                new HttpCookie[] {selector("not-a-uuid")}, new HttpCookie[] {selector(A), selector(B)})) {
            var store = mock(TokenRevocationStore.class);
            var gate = mock(PermissionGate.class);
            when(store.isRevoked("access-a")).thenReturn(Mono.just(false));
            when(gate.browserSessionActive(42L, "alice", 2L, A)).thenReturn(Mono.just(true));
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                    .cookie(cookies).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessA()).build());
            var forwarded = forwarded(exchange, store, gate);
            var proof = GatewayTestKeys.user(forwarded.getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER), "metadata");
            assertEquals(42L, proof.uid());
            assertEquals("alice", proof.username());
            assertEquals(2L, proof.accessVersion());
            assertNull(forwarded.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            verify(gate).browserSessionActive(42L, "alice", 2L, A);
            verify(store).isRevoked("access-a");
            verifyNoMoreInteractions(gate, store);
        }
    }

    @Test
    void invalidBearerNeverFallsBackToValidSelectedCookies() {
        for (String bearer : List.of(
                jwt("access", "bob", 84L, 7L, null, "missing-sid"),
                jwt("access", "bob", 84L, 7L, "not-a-uuid", "bad-sid"),
                jwt("access", "bob", 84L, 7L, B.toUpperCase(Locale.ROOT), "noncanonical-sid"),
                jwt("access", "bob", 84L, null, B, "missing-av"),
                jwt("access", "bob", 84L, -1L, B, "negative-av"),
                logout("bob", 84L, 7L, B), accessB() + "invalid-signature", "")) {
            rejected(MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                    .cookie(selector(B), credential(BrowserSessionCookies.ACCESS, B, accessB())).build()));
        }
    }

    @Test
    void anOldFamilyLogoutNeverBlacklistsBWhenTheSelectedCapabilityDoesNotMatch() {
        // A's leftover capability is present, but cannot authorize revocation of B's selected access.
        var oldCookieRequest = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                .cookie(selector(B), credential(BrowserSessionCookies.ACCESS, B, accessB()),
                        credential(BrowserSessionCookies.LOGOUT, A, logout("alice", 42L, 2L, A))).build());
        logoutWithoutBlacklist(oldCookieRequest);

        // An old logout request can also arrive while a caller presents a newer Bearer.
        var oldSelectorRequest = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessB())
                .cookie(selector(A), credential(BrowserSessionCookies.LOGOUT, A, logout("alice", 42L, 2L, A)),
                        credential(BrowserSessionCookies.ACCESS, B, accessB())).build());
        logoutWithoutBlacklist(oldSelectorRequest);
    }

    @Test
    void logoutCapabilityMustMatchAccessSidUidVersionAndSubject() {
        for (String mismatched : List.of(
                logout("bob", 84L, 7L, A), logout("bob", 42L, 7L, B),
                logout("bob", 84L, 6L, B), logout("alice", 84L, 7L, B))) {
            logoutWithoutBlacklist(MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                    .cookie(selector(B), credential(BrowserSessionCookies.ACCESS, B, accessB()),
                            credential(BrowserSessionCookies.LOGOUT, B, mismatched)).build()));
        }
    }

    @Test
    void matchingLogoutBlacklistsOnlyTheSelectedFamilyJtiAndForwardsToAuth() {
        var store = mock(TokenRevocationStore.class);
        var gate = mock(PermissionGate.class);
        when(store.revoke(eq("access-b"), any())).thenReturn(Mono.empty());
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/logout")
                .cookie(selector(B), credential(BrowserSessionCookies.ACCESS, A, accessA()),
                        credential(BrowserSessionCookies.ACCESS, B, accessB()),
                        credential(BrowserSessionCookies.LOGOUT, A, logout("alice", 42L, 2L, A)),
                        credential(BrowserSessionCookies.LOGOUT, B, logout("bob", 84L, 7L, B)))
                .header("X-Aetheris-Identity", "forged").build());
        var forwarded = forwarded(exchange, store, gate);
        assertEquals(B, forwarded.getRequest().getCookies().getFirst(BrowserSessionCookies.SELECTOR).getValue());
        assertNotNull(forwarded.getRequest().getCookies().getFirst(BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, B)));
        assertNull(forwarded.getRequest().getHeaders().getFirst("X-Aetheris-Identity"));
        assertNull(forwarded.getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER));
        verify(store).revoke(eq("access-b"), any());
        verifyNoMoreInteractions(store);
        verifyNoInteractions(gate);
    }

    @Test
    void ordinaryCookieReadsStillFailClosedWhenRedisIsDown() {
        var store = mock(TokenRevocationStore.class);
        var gate = mock(PermissionGate.class);
        when(store.isRevoked("access-b")).thenReturn(Mono.error(new IllegalStateException("redis fixture unavailable")));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/metadata/tables")
                .cookie(selector(B), credential(BrowserSessionCookies.ACCESS, B, accessB())).build());
        filter(store, gate).filter(exchange, ignored -> { throw new AssertionError("Redis failure must deny business reads"); }).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
        verify(store).isRevoked("access-b");
        verifyNoMoreInteractions(store);
        verifyNoInteractions(gate);
    }

    private static void rejected(MockServerWebExchange exchange) {
        var store = mock(TokenRevocationStore.class);
        var gate = mock(PermissionGate.class);
        filter(store, gate).filter(exchange, ignored -> { throw new AssertionError("Invalid credential must not reach downstream"); }).block();
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verifyNoInteractions(store, gate);
    }

    private static void logoutWithoutBlacklist(MockServerWebExchange exchange) {
        var store = mock(TokenRevocationStore.class);
        var gate = mock(PermissionGate.class);
        var forwarded = forwarded(exchange, store, gate);
        assertNull(forwarded.getRequest().getHeaders().getFirst(IdentitySigner.IDENTITY_HEADER));
        verifyNoInteractions(store, gate);
    }

    private static ServerWebExchange forwarded(MockServerWebExchange exchange, TokenRevocationStore store, PermissionGate gate) {
        var observed = new AtomicReference<ServerWebExchange>();
        filter(store, gate).filter(exchange, next -> { observed.set(next); return next.getResponse().setComplete(); }).block();
        assertNotNull(observed.get(), "Expected the fixed route to reach downstream");
        assertNull(exchange.getResponse().getStatusCode());
        return observed.get();
    }

    private static JwtAuthFilter filter(TokenRevocationStore store, PermissionGate gate) {
        return new JwtAuthFilter(KEY, store, gate, GatewayTestKeys.identitySigner());
    }

    private static HttpCookie selector(String sid) { return new HttpCookie(BrowserSessionCookies.SELECTOR, sid); }
    private static HttpCookie credential(String purpose, String sid, String value) {
        return new HttpCookie(BrowserSessionCookies.name(purpose, sid), value);
    }
    private static String accessA() { return jwt("access", "alice", 42L, 2L, A, "access-a"); }
    private static String accessB() { return jwt("access", "bob", 84L, 7L, B, "access-b"); }
    private static String logout(String username, long uid, long av, String sid) {
        return jwt("browser-logout", username, uid, av, sid, "logout-" + sid);
    }
    private static String jwt(String type, String username, long uid, Long av, String sid, String jti) {
        var builder = Jwts.builder().id(jti).subject(username).claim("uid", uid).claim("typ", type)
                .expiration(new Date(System.currentTimeMillis() + 60_000));
        if (av != null) builder.claim("av", av);
        if (sid != null) builder.claim("sid", sid);
        return builder.signWith(KEY).compact();
    }
}
