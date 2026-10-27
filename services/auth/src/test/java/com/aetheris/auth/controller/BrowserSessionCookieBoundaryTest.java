package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real cookie parsing and JWTs; the session store is a fixture, not PostgreSQL or browser acceptance. */
class BrowserSessionCookieBoundaryTest {
    private static final String SELECTOR = "aetheris_session";
    private static final String ACCESS = "aetheris_access";
    private static final String REFRESH = "aetheris_refresh";
    private static final String LOGOUT = "aetheris_logout";
    private static final UUID A = UUID.fromString("a17f0c3e-2526-4df0-b07b-335bcedfbd60");
    private static final UUID B = UUID.fromString("b28e1d4f-3637-4e01-811c-446cdfe0ce71");

    @Test void publicMappingsReadFamilyCredentialsFromTheServletRequest() throws Exception {
        var f = new Fixture();
        when(f.sessions.rotate(A, 9, "alice", 8, 3)).thenReturn(Optional.of(f.session(A, 4)));
        var mvc = MockMvcBuilders.standaloneSetup(f.controller).build();
        var refreshed = mvc.perform(post("/api/auth/refresh").cookie(
                new Cookie(SELECTOR, A.toString()), new Cookie(name(REFRESH, A), f.refresh(A))))
                .andReturn().getResponse();
        assertEquals(200, refreshed.getStatus());
        assertEquals(Set.of(name(ACCESS, A), name(REFRESH, A)), names(refreshed.getHeaders(HttpHeaders.SET_COOKIE)));
        var loggedOut = mvc.perform(post("/api/auth/logout").cookie(
                new Cookie(SELECTOR, A.toString()), new Cookie(name(LOGOUT, A), f.logout(A))))
                .andReturn().getResponse();
        assertEquals(204, loggedOut.getStatus());
        assertEquals(Set.of(name(ACCESS, A), name(REFRESH, A), name(LOGOUT, A)), names(loggedOut.getHeaders(HttpHeaders.SET_COOKIE)));
        verify(f.sessions).rotate(A, 9, "alice", 8, 3);
        verify(f.sessions).revoke(A, 9, "alice", 8);
    }

    @Test void missingOrNoncanonicalSelectorNeverAdoptsAValidCredential() {
        var f = new Fixture();
        var selectors = new ArrayList<String>();
        selectors.add(null);
        selectors.addAll(List.of("", "not-a-uuid", "1-1-1-1-1", A.toString().toUpperCase(Locale.ROOT), A + " "));
        for (String selector : selectors) {
            var cookies = new ArrayList<Cookie>();
            if (selector != null) cookies.add(new Cookie(SELECTOR, selector));
            cookies.add(new Cookie(name(REFRESH, A), f.refresh(A)));
            cookies.add(new Cookie(name(LOGOUT, A), f.logout(A)));
            var refreshed = f.controller.refresh(request(cookies.toArray(Cookie[]::new)));
            var loggedOut = f.controller.logout(request(cookies.toArray(Cookie[]::new)));
            assertEquals(401, refreshed.getStatusCode().value(), String.valueOf(selector));
            assertEquals(401, loggedOut.getStatusCode().value(), String.valueOf(selector));
            writesExactly(refreshed);
            writesExactly(loggedOut);
        }
        verifyNoInteractions(f.sessions);
    }

    @Test void repeatedSelectorIsRejectedEvenWhenBothValuesAreIdentical() {
        var f = new Fixture();
        for (UUID second : List.of(A, B)) {
            var input = request(new Cookie(SELECTOR, A.toString()), new Cookie(SELECTOR, second.toString()),
                    new Cookie(name(REFRESH, A), f.refresh(A)), new Cookie(name(LOGOUT, A), f.logout(A)));
            var refreshed = f.controller.refresh(input);
            var loggedOut = f.controller.logout(input);
            assertEquals(401, refreshed.getStatusCode().value());
            assertEquals(401, loggedOut.getStatusCode().value());
            writesExactly(refreshed);
            writesExactly(loggedOut);
        }
        verifyNoInteractions(f.sessions);
    }

    @Test void repeatedSelectedCredentialIsRejectedRatherThanPickingTheFirstOrLastValue() {
        var f = new Fixture();
        for (String secondRefresh : List.of(f.refresh(A), "invalid-second-value")) {
            var response = f.controller.refresh(request(new Cookie(SELECTOR, A.toString()),
                    new Cookie(name(REFRESH, A), f.refresh(A)), new Cookie(name(REFRESH, A), secondRefresh)));
            assertEquals(401, response.getStatusCode().value());
            writesExactly(response);
        }
        for (String secondLogout : List.of(f.logout(A), "invalid-second-value")) {
            var response = f.controller.logout(request(new Cookie(SELECTOR, A.toString()),
                    new Cookie(name(LOGOUT, A), f.logout(A)), new Cookie(name(LOGOUT, A), secondLogout)));
            assertEquals(401, response.getStatusCode().value());
            clearsOnly(response, A, true);
        }
        verifyNoInteractions(f.sessions);
    }

    @Test void selectedCookieNameCannotCarryATokenForAnotherSid() {
        var f = new Fixture();
        var refreshed = f.controller.refresh(f.withOtherFamily(REFRESH, f.refresh(B)));
        var loggedOut = f.controller.logout(f.withOtherFamily(LOGOUT, f.logout(B)));
        assertEquals(401, refreshed.getStatusCode().value());
        assertEquals(401, loggedOut.getStatusCode().value());
        writesExactly(refreshed);
        clearsOnly(loggedOut, A, true);
        verifyNoInteractions(f.sessions);
    }

    @Test void aWrongSelectorNeverFallsBackToOtherFamilyOrLegacyCredentials() {
        var f = new Fixture();
        var input = request(new Cookie(SELECTOR, B.toString()),
                new Cookie(name(REFRESH, A), f.refresh(A)), new Cookie(REFRESH, f.refresh(A)),
                new Cookie(name(LOGOUT, A), f.logout(A)), new Cookie(LOGOUT, f.logout(A)));
        var refreshed = f.controller.refresh(input);
        var loggedOut = f.controller.logout(input);
        assertEquals(401, refreshed.getStatusCode().value());
        assertEquals(401, loggedOut.getStatusCode().value());
        writesExactly(refreshed);
        clearsOnly(loggedOut, B, true);
        verifyNoInteractions(f.sessions);
    }

    @Test void delayedSuccessfulRefreshWritesOnlyItsOriginalFamilyAndNeverTheSelector() {
        var f = new Fixture();
        when(f.sessions.rotate(A, 9, "alice", 8, 3)).thenReturn(Optional.of(f.session(A, 4)));
        var response = f.controller.refresh(f.withOtherFamily(REFRESH, f.refresh(A)));
        assertEquals(200, response.getStatusCode().value());
        writesExactly(response, name(ACCESS, A), name(REFRESH, A));
        assertEquals("no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        var next = f.jwt.parseRefreshToken(value(response, name(REFRESH, A)));
        assertEquals(A, next.sid()); assertEquals(4, next.refreshVersion()); assertEquals(8, next.accessVersion());
        assertTrue(header(response, name(ACCESS, A)).contains("Path=/;"));
        assertTrue(header(response, name(REFRESH, A)).contains("Path=/api/auth;"));
        assertTrue(setCookies(response).stream().allMatch(cookie -> cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax")));
    }

    @Test void successfulLogoutClearsOnlyTheOriginalFamilyWithoutWritingTheSelector() {
        var f = new Fixture();
        var response = f.controller.logout(f.withOtherFamily(LOGOUT, f.logout(A)));
        assertEquals(204, response.getStatusCode().value());
        clearsOnly(response, A, true);
        verify(f.sessions).revoke(A, 9, "alice", 8);
        verifyNoInteractions(f.users);
    }

    @Test void invalidLogoutClearsOnlyTheSelectedFamilyWithoutTouchingOtherFamilies() {
        var f = new Fixture();
        var response = f.controller.logout(f.withOtherFamily(LOGOUT, "invalid-proof"));
        assertEquals(401, response.getStatusCode().value());
        assertEquals("BROWSER_SESSION_LOGOUT_UNCONFIRMED", response.getBody().get("code"));
        clearsOnly(response, A, true);
        verifyNoInteractions(f.sessions);
    }

    @Test void unavailableLogoutPreservesItsRetryCapabilityAndEveryOtherFamily() {
        var f = new Fixture();
        doThrow(new DataAccessResourceFailureException("fixture unavailable")).when(f.sessions).revoke(A, 9, "alice", 8);
        var response = f.controller.logout(f.withOtherFamily(LOGOUT, f.logout(A)));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("BROWSER_SESSION_LOGOUT_UNCONFIRMED", response.getBody().get("code"));
        clearsOnly(response, A, false);
        verify(f.sessions).revoke(A, 9, "alice", 8);
    }

    @Test void rejectedOrUnavailableRefreshWritesNoCookieOfAnyFamily() {
        var f = new Fixture();
        when(f.sessions.rotate(A, 9, "alice", 8, 3)).thenReturn(Optional.empty())
                .thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        for (int expected : List.of(401, 503)) {
            var response = f.controller.refresh(f.withOtherFamily(REFRESH, f.refresh(A)));
            assertEquals(expected, response.getStatusCode().value());
            writesExactly(response);
        }
    }

    @Test void accessRefreshAndLogoutTokenTypesCannotBeInterchanged() {
        var f = new Fixture();
        for (String token : List.of(f.jwt.generateToken(f.session(A, 3)), f.logout(A))) {
            var response = f.controller.refresh(f.withOtherFamily(REFRESH, token));
            assertEquals(401, response.getStatusCode().value()); writesExactly(response);
        }
        for (String token : List.of(f.jwt.generateToken(f.session(A, 3)), f.refresh(A))) {
            var response = f.controller.logout(f.withOtherFamily(LOGOUT, token));
            assertEquals(401, response.getStatusCode().value()); clearsOnly(response, A, true);
        }
        verifyNoInteractions(f.sessions);
    }

    @Test void passwordLoginWritesSelectorAndRetiresOnlyRecognizedPriorCredentials() {
        var f = new Fixture();
        var cookies = new ArrayList<Cookie>();
        cookies.add(new Cookie(SELECTOR, A.toString()));
        for (String purpose : List.of(ACCESS, REFRESH, LOGOUT)) {
            cookies.add(new Cookie(name(purpose, A), "old-family"));
            cookies.add(new Cookie(purpose, "legacy"));
            cookies.add(new Cookie(name(purpose, B), "already-present-current-family"));
        }
        cookies.add(new Cookie(name(ACCESS, A), "duplicate-old-name"));
        cookies.add(new Cookie("custom_session", "untouched"));
        cookies.add(new Cookie(ACCESS + "_not-a-uuid", "untouched"));
        cookies.add(new Cookie(REFRESH + "_" + A + "_extra", "untouched"));
        cookies.add(new Cookie(LOGOUT + "_" + A.toString().toUpperCase(Locale.ROOT), "untouched"));
        var response = f.controller.login(new LoginRequest("alice", "password"), request(cookies.toArray(Cookie[]::new)));
        assertEquals(200, response.getStatusCode().value());
        writesExactly(response, SELECTOR, name(ACCESS, B), name(REFRESH, B), name(LOGOUT, B),
                ACCESS, REFRESH, LOGOUT, name(ACCESS, A), name(REFRESH, A), name(LOGOUT, A));
        assertEquals(B.toString(), value(response, SELECTOR));
        assertTrue(header(response, SELECTOR).contains("HttpOnly"));
        assertTrue(header(response, SELECTOR).contains("Path=/;"));
        for (String purpose : List.of(ACCESS, REFRESH, LOGOUT)) {
            assertTrue(header(response, purpose).contains("Max-Age=0"));
            assertTrue(header(response, name(purpose, A)).contains("Max-Age=0"));
            assertFalse(value(response, name(purpose, B)).isBlank());
            assertFalse(header(response, name(purpose, B)).contains("Max-Age=0"));
        }
        assertEquals(B, f.jwt.parseRefreshToken(value(response, name(REFRESH, B))).sid());
        assertEquals(B, f.jwt.parseLogoutToken(value(response, name(LOGOUT, B))).sid());
    }

    @Test void failedPasswordLoginCannotReplaceSelectorOrRetireExistingCookies() {
        var f = new Fixture();
        when(f.encoder.matches("wrong", "hash")).thenReturn(false);
        var response = f.controller.login(new LoginRequest("alice", "wrong"), f.withOtherFamily(LOGOUT, f.logout(A)));
        assertEquals(401, response.getStatusCode().value());
        writesExactly(response);
        verifyNoInteractions(f.sessions);
    }

    private static String name(String purpose, UUID sid) { return purpose + "_" + sid; }
    private static MockHttpServletRequest request(Cookie... cookies) {
        var request = new MockHttpServletRequest(); request.setCookies(cookies); return request;
    }
    private static List<String> setCookies(ResponseEntity<?> response) { return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE); }
    private static Set<String> names(Collection<String> cookies) {
        return cookies.stream().map(value -> value.substring(0, value.indexOf('='))).collect(Collectors.toSet());
    }
    private static void writesExactly(ResponseEntity<?> response, String... expected) {
        assertEquals(Set.of(expected), names(setCookies(response)));
        assertEquals(expected.length, setCookies(response).size(), "A cookie name must not be emitted twice");
    }
    private static String header(ResponseEntity<?> response, String name) {
        return setCookies(response).stream().filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
    }
    private static String value(ResponseEntity<?> response, String name) {
        String raw = header(response, name); return raw.substring(name.length() + 1, raw.indexOf(';'));
    }
    private static void clearsOnly(ResponseEntity<?> response, UUID sid, boolean logout) {
        if (logout) writesExactly(response, name(ACCESS, sid), name(REFRESH, sid), name(LOGOUT, sid));
        else writesExactly(response, name(ACCESS, sid), name(REFRESH, sid));
        assertTrue(setCookies(response).stream().allMatch(value -> value.contains("Max-Age=0")));
        assertTrue(header(response, name(ACCESS, sid)).contains("Path=/;"));
        assertTrue(header(response, name(REFRESH, sid)).contains("Path=/api/auth;"));
        if (logout) assertTrue(header(response, name(LOGOUT, sid)).contains("Path=/api/auth;"));
    }

    private static final class Fixture {
        final AuthUserService users = mock(AuthUserService.class);
        final PasswordEncoder encoder = mock(PasswordEncoder.class);
        final BrowserSessionService sessions = mock(BrowserSessionService.class);
        final JwtService jwt = new JwtService("browser-cookie-boundary-test-secret-32-bytes", 60_000, 600_000);
        final Instant expiry = Instant.now().plusSeconds(600);
        final AuthController controller;
        BrowserSessionService.Session session(UUID sid, long version) {
            return new BrowserSessionService.Session(sid, 9, "alice", "viewer", null, 8, version, expiry);
        }
        String refresh(UUID sid) { return jwt.generateRefreshToken(session(sid, 3)); }
        String logout(UUID sid) { return jwt.generateLogoutToken(session(sid, 3)); }
        MockHttpServletRequest withOtherFamily(String purpose, String token) {
            return request(new Cookie(SELECTOR, A.toString()), new Cookie(name(purpose, A), token),
                    new Cookie(name(ACCESS, B), jwt.generateToken(session(B, 0))),
                    new Cookie(name(REFRESH, B), refresh(B)), new Cookie(name(LOGOUT, B), logout(B)),
                    new Cookie(ACCESS, "legacy-access"), new Cookie(REFRESH, "legacy-refresh"),
                    new Cookie(LOGOUT, "legacy-logout"), new Cookie("custom_session", "untouched"));
        }
        Fixture() {
            AuthUser user = new AuthUser(); user.setId(9L); user.setUsername("alice"); user.setPasswordHash("hash");
            user.setStatus("active"); user.setRole("viewer"); user.setAccessVersion(8L); user.setSessionVersion(3L);
            when(users.findByUsername("alice")).thenReturn(Optional.of(user));
            when(encoder.matches("password", "hash")).thenReturn(true);
            when(sessions.open(eq(user), any())).thenReturn(Optional.of(session(B, 0)));
            var versions = mock(PermissionVersionService.class); when(versions.current()).thenReturn(17L);
            controller = new AuthController(users, encoder, jwt, new LoginAttemptService(), versions, sessions);
        }
    }
}
