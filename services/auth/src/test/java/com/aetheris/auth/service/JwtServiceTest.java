package com.aetheris.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

    private static final String SECRET = "aetheris-test-secret-with-32-bytes";

    @Test
    void rejectsShortSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 60_000));
    }

    @Test
    void generatesSignedTokenForUsername() {
        JwtService service = new JwtService(SECRET, 60_000);

        var claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(service.generateToken("alice", "admin"))
                .getPayload();

        assertEquals("alice", claims.getSubject());
        assertEquals("admin", claims.get("role", String.class));
    }
}
