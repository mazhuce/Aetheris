package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.*;
import com.aetheris.security.protocol.BrowserSessionCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthControllerTest {
    @Test void concurrentRefreshRotatesOnceAndReplayNeverClearsWinnerCookies() throws Exception {
        var f = new Fixture();
        var version = new AtomicLong(3);
        when(f.sessions.rotate(f.sid, 9, "alice", 8, 3)).thenAnswer(ignored ->
                version.compareAndSet(3, 4) ? Optional.of(f.session(4)) : Optional.empty());
        String refresh = f.jwt.generateRefreshToken(f.session(3));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> f.controller.refresh(browserRequest(f.sid, AuthController.REFRESH_COOKIE, refresh)));
            var second = executor.submit(() -> f.controller.refresh(browserRequest(f.sid, AuthController.REFRESH_COOKIE, refresh)));
            var responses = List.of(first.get(), second.get());
            assertEquals(1, responses.stream().filter(r -> r.getStatusCode().value() == 200).count());
            var loser = responses.stream().filter(r -> r.getStatusCode().value() == 401).findFirst().orElseThrow();
            assertFalse(loser.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
        }
        var replay = f.controller.refresh(browserRequest(f.sid, AuthController.REFRESH_COOKIE, refresh));
        assertEquals(401, replay.getStatusCode().value());
        assertFalse(replay.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
        verify(f.users, never()).update(isNull(), any());
    }

    @Test void rejectedRefreshCannotEraseCookiesSetByConcurrentWinner() {
        var f = new Fixture();
        var response = f.controller.refresh(browserRequest(f.sid, AuthController.REFRESH_COOKIE, f.jwt.generateRefreshToken(f.session(3))));
        assertEquals(401, response.getStatusCode().value());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
    }

    @Test void loginSupportsUserWithoutNicknameAndReturnsStableSubjectWithoutTokens() {
        var f = new Fixture();
        var response = f.controller.login(new LoginRequest("alice", "password"));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(9L, response.getBody().get("id"));
        assertEquals(8L, response.getBody().get("accessVersion"));
        assertEquals(17L, response.getBody().get("permissionVersion"));
        assertFalse(response.getBody().containsKey("token"));
        assertFalse(response.getBody().containsKey("nickname"));
        assertEquals("viewer", response.getBody().get("role"));
        assertEquals(4, response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).size());
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals(f.sid.toString(), cookie(response, BrowserSessionCookies.SELECTOR));
        var refresh = f.jwt.parseRefreshToken(cookie(response, BrowserSessionCookies.name(AuthController.REFRESH_COOKIE, f.sid.toString())));
        var logout = f.jwt.parseLogoutToken(cookie(response, BrowserSessionCookies.name(AuthController.LOGOUT_COOKIE, f.sid.toString())));
        assertEquals(refresh.sid(), logout.sid());
        assertEquals(8, logout.accessVersion());
    }

    @Test void rejectsWhitespaceUsernameBeforeLookupAndTokenIssue() {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> f.controller.login(new LoginRequest("  alice  ", "password")));
        verifyNoInteractions(f.users, f.encoder, f.sessions);
    }

    @Test void refreshRotatesOnlyFamilyVersionAndNeverReissuesLogoutCapability() {
        var f = new Fixture();
        when(f.sessions.rotate(f.sid, 9, "alice", 8, 3)).thenReturn(Optional.of(f.session(4)));
        var response = f.controller.refresh(browserRequest(f.sid, AuthController.REFRESH_COOKIE, f.jwt.generateRefreshToken(f.session(3))));
        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().containsKey("token"));
        assertEquals(9L, response.getBody().get("id"));
        assertEquals(8L, response.getBody().get("accessVersion"));
        assertEquals(17L, response.getBody().get("permissionVersion"));
        assertEquals(2, response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).size());
        assertTrue(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream().noneMatch(v -> v.startsWith(AuthController.LOGOUT_COOKIE + "_") || v.startsWith(BrowserSessionCookies.SELECTOR + "=")));
        var next = f.jwt.parseRefreshToken(cookie(response, BrowserSessionCookies.name(AuthController.REFRESH_COOKIE, f.sid.toString())));
        assertEquals(f.sid, next.sid()); assertEquals(4, next.refreshVersion());
        verifyNoInteractions(f.users);
    }

    @Test void locksAccountAfterRepeatedFailures() {
        var f = new Fixture();
        when(f.users.findByUsername("alice")).thenReturn(Optional.empty());
        var request = new LoginRequest("alice", "wrong");
        for (int attempt = 0; attempt < 5; attempt++) assertEquals(401, f.controller.login(request).getStatusCode().value());
        assertEquals(429, f.controller.login(request).getStatusCode().value());
        verifyNoInteractions(f.sessions);
    }

    @Test void logoutCapabilityRevokesOriginalFamilyWithoutAccountMutationAndIsIdempotent() {
        var f = new Fixture();
        String proof = f.jwt.generateLogoutToken(f.session(0));
        for (int i = 0; i < 2; i++) {
            var response = f.controller.logout(browserRequest(f.sid, AuthController.LOGOUT_COOKIE, proof));
            assertEquals(204, response.getStatusCode().value());
            assertEquals(3, response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).size());
            assertTrue(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream().noneMatch(v -> v.startsWith(BrowserSessionCookies.SELECTOR + "=")));
        }
        verify(f.sessions, times(2)).revoke(f.sid, 9, "alice", 8);
        verifyNoInteractions(f.users);
    }

    @Test void consumedRefreshAndAccessCannotSubstituteForIndependentLogoutProof() {
        var f = new Fixture();
        for (String proof : List.of(f.jwt.generateToken(f.session(3)), f.jwt.generateRefreshToken(f.session(3)), "")) {
            var response = f.controller.logout(browserRequest(f.sid, AuthController.LOGOUT_COOKIE, proof));
            assertEquals(401, response.getStatusCode().value());
            assertEquals("BROWSER_SESSION_LOGOUT_UNCONFIRMED", response.getBody().get("code"));
        }
        verifyNoInteractions(f.sessions, f.users);
    }

    private static MockHttpServletRequest browserRequest(UUID sid, String purpose, String token) {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(BrowserSessionCookies.SELECTOR, sid.toString()),
                new Cookie(BrowserSessionCookies.name(purpose, sid.toString()), token));
        return request;
    }

    private static String cookie(ResponseEntity<?> response, String name) {
        String raw = response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return raw.substring(name.length() + 1, raw.indexOf(';'));
    }
    private static final class Fixture {
        final AuthUserService users = mock(AuthUserService.class);
        final PasswordEncoder encoder = mock(PasswordEncoder.class);
        final BrowserSessionService sessions = mock(BrowserSessionService.class);
        final JwtService jwt = new JwtService("aetheris-test-secret-with-32-bytes", 60_000, 600_000);
        final UUID sid = UUID.randomUUID();
        final Instant expires = Instant.now().plusSeconds(600);
        final AuthController controller;
        BrowserSessionService.Session session(long version) {
            return new BrowserSessionService.Session(sid, 9, "alice", "viewer", null, 8, version, expires);
        }
        Fixture() {
            AuthUser user = new AuthUser(); user.setId(9L); user.setUsername("alice"); user.setPasswordHash("hash");
            user.setStatus("active"); user.setRole("viewer"); user.setSessionVersion(3L); user.setAccessVersion(8L);
            when(users.findByUsername("alice")).thenReturn(Optional.of(user));
            when(encoder.matches("password", "hash")).thenReturn(true);
            when(sessions.open(eq(user), any())).thenReturn(Optional.of(session(0)));
            var versions = mock(PermissionVersionService.class); when(versions.current()).thenReturn(17L);
            controller = new AuthController(users, encoder, jwt, new LoginAttemptService(), versions, sessions);
        }
    }
}
