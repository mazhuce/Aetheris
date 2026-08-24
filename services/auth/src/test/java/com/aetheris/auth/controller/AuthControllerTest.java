package com.aetheris.auth.controller;

import com.aetheris.auth.dto.LoginRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.auth.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void loginSupportsUserWithoutNickname() {
        AuthUserService userService = mock(AuthUserService.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        AuthUser user = new AuthUser();
        user.setUsername("alice");
        user.setPasswordHash("hash");
        user.setRole("viewer");
        user.setStatus("active");
        when(userService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(encoder.matches("password", "hash")).thenReturn(true);
        when(jwtService.generateToken("alice", "viewer")).thenReturn("token");
        when(jwtService.getExpirationMs()).thenReturn(60_000L);

        ResponseEntity<Map<String, Object>> response = new AuthController(userService, encoder, jwtService)
                .login(new LoginRequest("alice", "password"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("token", response.getBody().get("token"));
        assertEquals("viewer", response.getBody().get("role"));
        assertFalse(response.getBody().containsKey("nickname"));
    }
}
