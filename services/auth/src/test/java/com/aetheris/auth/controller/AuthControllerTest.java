package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.auth.service.JwtService;
import com.aetheris.auth.service.LoginAttemptService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;

class AuthControllerTest {

    @Test
    void concurrentRefreshRotatesOnceAndReplayNeverClearsWinnerCookies() throws Exception {
        AuthUserService users = mock(AuthUserService.class);
        JwtService jwt = mock(JwtService.class);
        AuthUser user = new AuthUser(); user.setId(9L); user.setUsername("alice");
        user.setStatus("active"); user.setRole("viewer"); user.setSessionVersion(3L);
        Claims claims = Jwts.claims().subject("alice").add("uid", 9L).add("sv", 3L).build();
        when(jwt.parseRefreshToken("same-refresh")).thenReturn(claims);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong(3);
        when(users.update(isNull(), any())).thenAnswer(ignored -> version.compareAndSet(3, 4));
        when(jwt.generateToken(9L, "alice", "viewer", 0L)).thenReturn("new-access");
        when(jwt.generateRefreshToken(9L, "alice", 4L)).thenReturn("new-refresh");
        when(jwt.getExpirationMs()).thenReturn(60_000L);
        when(jwt.getRefreshExpirationMs()).thenReturn(604_800_000L);
        var controller = new AuthController(users, mock(PasswordEncoder.class), jwt, new LoginAttemptService());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> controller.refresh("same-refresh"));
            var second = executor.submit(() -> controller.refresh("same-refresh"));
            var responses = java.util.List.of(first.get(), second.get());
            assertEquals(1, responses.stream().filter(r -> r.getStatusCode().is2xxSuccessful()).count());
            var loser = responses.stream().filter(r -> r.getStatusCode().value() == 401).findFirst().orElseThrow();
            assertFalse(loser.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
        }
        var replay = controller.refresh("same-refresh");
        assertEquals(HttpStatus.UNAUTHORIZED, replay.getStatusCode());
        assertFalse(replay.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
    }

    @Test
    void rejectedRefreshCannotEraseCookiesSetByConcurrentWinner() {
        AuthUserService users = mock(AuthUserService.class);
        JwtService jwt = mock(JwtService.class);
        Claims claims = Jwts.claims().subject("alice").add("uid", 9L).add("sv", 3L).build();
        AuthUser user = new AuthUser();
        user.setId(9L); user.setUsername("alice"); user.setStatus("active"); user.setSessionVersion(3L);
        when(jwt.parseRefreshToken("old-refresh")).thenReturn(claims);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(users.update(isNull(), any())).thenReturn(false);
        var response = new AuthController(users, mock(PasswordEncoder.class), jwt,
                new LoginAttemptService()).refresh("old-refresh");
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE));
    }

    @Test
    void loginSupportsUserWithoutNickname() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AuthUser user = new AuthUser();
        user.setId(7L);
        user.setUsername("alice");
        user.setPasswordHash("hash");
        user.setRole("viewer");
        user.setStatus("active");
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(encoder.matches("password", "hash")).thenReturn(true);
        when(jwtService.generateToken(7L, "alice", "viewer", 0L)).thenReturn("token");
        when(jwtService.generateRefreshToken(7L, "alice", 0L)).thenReturn("refresh-token");
        when(jwtService.getExpirationMs()).thenReturn(60_000L);
        when(jwtService.getRefreshExpirationMs()).thenReturn(604_800_000L);

        ResponseEntity<Map<String, Object>> response =
                new AuthController(userService, encoder, jwtService, new LoginAttemptService())
                        .login(new LoginRequest("alice", "password"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(null, response.getBody().get("token"));
        assertEquals("viewer", response.getBody().get("role"));
        assertFalse(response.getBody().containsKey("nickname"));
        assertEquals(2, response.getHeaders().get(HttpHeaders.SET_COOKIE).size());
    }

    @Test
    void rejectsWhitespaceUsernameBeforeLookupAndTokenIssue() {
        AuthUserService users = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        var controller = new AuthController(users, encoder, jwt, new LoginAttemptService());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.login(new LoginRequest("  alice  ", "password")));
        org.mockito.Mockito.verifyNoInteractions(users, encoder, jwt);
    }

    @Test
    void refreshRotatesSessionVersionAndNeverReturnsAccessTokenBody() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AuthUser user = new AuthUser();
        user.setId(9L);
        user.setUsername("alice");
        user.setRole("viewer");
        user.setStatus("active");
        user.setSessionVersion(3L);
        Claims claims = Jwts.claims().subject("alice")
                .add("uid", 9L).add("sv", 3L).build();
        when(jwtService.parseRefreshToken("refresh-token")).thenReturn(claims);
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(userService.update(isNull(), any())).thenReturn(true);
        when(jwtService.generateToken(9L, "alice", "viewer", 0L)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(9L, "alice", 4L)).thenReturn("next-refresh");
        when(jwtService.getExpirationMs()).thenReturn(60_000L);
        when(jwtService.getRefreshExpirationMs()).thenReturn(604_800_000L);

        ResponseEntity<Map<String, Object>> response = new AuthController(
                userService, encoder, jwtService, new LoginAttemptService()).refresh("refresh-token");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertFalse(response.getBody().containsKey("token"));
        assertEquals(2, response.getHeaders().get(HttpHeaders.SET_COOKIE).size());
        verify(userService).update(any(), any());
    }

    @Test
    void locksAccountAfterRepeatedFailures() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        when(userService.findByUsername("alice")).thenReturn(Optional.empty());
        AuthController controller =
                new AuthController(userService, encoder, jwtService, new LoginAttemptService());
        LoginRequest request = new LoginRequest("alice", "wrong");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(HttpStatus.UNAUTHORIZED, controller.login(request).getStatusCode());
        }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, controller.login(request).getStatusCode());
    }
}
