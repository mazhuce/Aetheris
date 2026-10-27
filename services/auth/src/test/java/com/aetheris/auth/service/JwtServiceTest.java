package com.aetheris.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "aetheris-test-secret-with-32-bytes";
    private static final javax.crypto.SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private final JwtService service = new JwtService(SECRET, 60_000);
    private BrowserSessionService.Session session(Instant expiry) {
        return new BrowserSessionService.Session(UUID.randomUUID(), 42, "alice", "viewer", null, 7, 12, expiry);
    }
    @Test void accessRevocationVersionIsIndependentFromRefreshRotation() {
        var session = session(Instant.now().plusSeconds(600));
        var parser = Jwts.parser().verifyWith(KEY).build();
        var access = parser.parseSignedClaims(service.generateToken(session)).getPayload();
        var refresh = service.parseRefreshToken(service.generateRefreshToken(session));
        assertEquals(7L, access.get("av", Long.class)); assertEquals(7, refresh.accessVersion());
        assertEquals(12, refresh.refreshVersion()); assertNull(access.get("rv"));
        assertEquals(session.sid().toString(), access.get("sid")); assertEquals(session.sid(), refresh.sid());
    }
    @Test void rejectsShortSecretAndInvalidLifetimes() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 60_000));
        assertThrows(IllegalStateException.class, () -> new JwtService(SECRET, 0));
        assertThrows(IllegalStateException.class, () -> new JwtService(SECRET, 60_000, -1));
    }
    @Test void generatesSignedTokenForStableSubjectAndFamily() {
        var session = session(Instant.now().plusSeconds(600));
        var claims = Jwts.parser().verifyWith(KEY).build().parseSignedClaims(service.generateToken(session)).getPayload();
        assertEquals("alice", claims.getSubject()); assertEquals("viewer", claims.get("role", String.class));
        assertEquals(42L, claims.get("uid", Long.class)); assertEquals("access", claims.get("typ", String.class));
        assertEquals(session.sid().toString(), claims.get("sid")); assertNotNull(claims.getId());
    }
    @Test void noTokenOutlivesFixedFamilyHorizon() {
        Instant horizon = Instant.now().plusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var session = session(horizon); var parser = Jwts.parser().verifyWith(KEY).build();
        for (String token : java.util.List.of(service.generateToken(session), service.generateRefreshToken(session), service.generateLogoutToken(session)))
            assertEquals(horizon, parser.parseSignedClaims(token).getPayload().getExpiration().toInstant());
    }
    @Test void refreshAndLogoutCapabilitiesAreNotInterchangeable() {
        var session = session(Instant.now().plusSeconds(600));
        assertThrows(io.jsonwebtoken.JwtException.class, () -> service.parseRefreshToken(service.generateLogoutToken(session)));
        assertThrows(io.jsonwebtoken.JwtException.class, () -> service.parseLogoutToken(service.generateRefreshToken(session)));
        assertThrows(io.jsonwebtoken.JwtException.class, () -> service.parseLogoutToken(service.generateToken(session)));
        assertEquals(session.sid(), service.parseLogoutToken(service.generateLogoutToken(session)).sid());
    }
    @Test void legacyRefreshWithoutFamilyIsNeverUpgradedByUsername() {
        String legacy = Jwts.builder().subject("alice").claim("uid", 42).claim("sv", 12).claim("typ", "refresh")
                .expiration(Date.from(Instant.now().plusSeconds(600))).signWith(KEY).compact();
        assertThrows(IllegalArgumentException.class, () -> service.parseRefreshToken(legacy));
    }
    @Test void nonIntegralOrStringSubjectClaimsAreRejected() {
        for (Object uid : java.util.List.of("42", 42.5)) {
            String token = Jwts.builder().subject("alice").claim("uid", uid).claim("av", 7).claim("rv", 12)
                    .claim("sid", UUID.randomUUID().toString()).claim("typ", "refresh")
                    .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(KEY).compact();
            assertThrows(IllegalArgumentException.class, () -> service.parseRefreshToken(token));
        }
    }
    @Test void expiredTokenFailsValidation() {
        var session = session(Instant.now().minusSeconds(5));
        assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () -> service.parseRefreshToken(service.generateRefreshToken(session)));
        assertThrows(io.jsonwebtoken.ExpiredJwtException.class, () -> service.parseLogoutToken(service.generateLogoutToken(session)));
    }
    @Test void tamperedTokenFailsValidation() {
        String token = service.generateRefreshToken(session(Instant.now().plusSeconds(600)));
        String[] parts = token.split("[.]");
        parts[1] = parts[1].substring(0, 5) + (parts[1].charAt(5) == 'A' ? 'B' : 'A') + parts[1].substring(6);
        assertThrows(io.jsonwebtoken.JwtException.class, () -> service.parseRefreshToken(String.join(".", parts)));
    }
}
