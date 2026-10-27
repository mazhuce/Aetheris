package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.*;
import com.aetheris.security.protocol.BrowserSessionCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Public browser contracts: infrastructure failures must never masquerade as logout success. */
class BrowserSessionBoundaryRegressionTest {
    private static final String SECRET = "browser-session-regression-secret-32-bytes";

    @Test void loginIssuesSeparateHttpOnlyFamilyRevocationCookie() {
        var fixture = new Fixture();
        var response = fixture.controller.login(new LoginRequest("alice", "password"));
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .anyMatch(value -> value.startsWith("aetheris_logout_" + fixture.session.sid() + "=") && value.contains("HttpOnly")
                        && value.contains("Path=/api/auth")), "Password login must issue an independent family logout proof");
    }

    @Test void refreshStorageFailureIsUnavailableAndDoesNotClearCookies() {
        var fixture = new Fixture();
        when(fixture.sessions.rotate(any(), anyLong(), anyString(), anyLong(), anyLong())).thenThrow(new DataAccessResourceFailureException("fixture unavailable"));
        var response = fixture.controller.refresh(fixture.request(AuthController.REFRESH_COOKIE, fixture.jwt.generateRefreshToken(fixture.session)));
        assertEquals(503, response.getStatusCode().value());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
    }

    @Test void logoutStorageFailureCannotClaimConfirmedRevocation() {
        var fixture = new Fixture();
        doThrow(new DataAccessResourceFailureException("fixture unavailable")).when(fixture.sessions).revoke(any(), anyLong(), anyString(), anyLong());
        var response = fixture.controller.logout(fixture.request(AuthController.LOGOUT_COOKIE, fixture.jwt.generateLogoutToken(fixture.session)));
        assertEquals(503, response.getStatusCode().value());
        assertTrue(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream().noneMatch(value -> value.startsWith("aetheris_logout_")),
                "Keep the independent capability for a retry when revocation was not confirmed");
        assertTrue(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream().noneMatch(value -> value.startsWith(BrowserSessionCookies.SELECTOR + "=")));
    }

    private static final class Fixture {
        final AuthUserService users = mock(AuthUserService.class);
        final BrowserSessionService sessions = mock(BrowserSessionService.class);
        final BrowserSessionService.Session session = new BrowserSessionService.Session(java.util.UUID.randomUUID(), 9, "alice",
                "viewer", null, 8, 3, java.time.Instant.now().plusSeconds(600));
        final JwtService jwt = new JwtService(SECRET, 60_000, 600_000);
        final AuthController controller;
        MockHttpServletRequest request(String purpose, String token) {
            var request = new MockHttpServletRequest();
            request.setCookies(new Cookie(BrowserSessionCookies.SELECTOR, session.sid().toString()),
                    new Cookie(BrowserSessionCookies.name(purpose, session.sid().toString()), token));
            return request;
        }
        Fixture() {
            AuthUser user = new AuthUser(); user.setId(9L); user.setUsername("alice");
            user.setStatus("active"); user.setRole("viewer"); user.setSessionVersion(3L);
            user.setAccessVersion(8L); user.setPasswordHash("hash");
            when(users.findByUsername("alice")).thenReturn(Optional.of(user));
            var encoder = mock(PasswordEncoder.class);
            when(encoder.matches("password", "hash")).thenReturn(true);
            when(sessions.open(eq(user), any())).thenReturn(Optional.of(session));
            controller = new AuthController(users, encoder, jwt, new LoginAttemptService(), mock(PermissionVersionService.class), sessions);
        }
    }
}
