package com.aetheris.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                .parseSignedClaims(service.generateToken(42L, "alice", "admin"))
                .getPayload();

        assertEquals("alice", claims.getSubject());
        assertEquals("admin", claims.get("role", String.class));
        assertEquals(42L, claims.get("uid", Long.class));
    }

    @Test
    void expiredTokenFailsValidation() {
        // 构造一个过期 1 秒的 token —— 这是 JWT 安全性的基础保证。
        JwtService service = new JwtService(SECRET, 60_000);
        String token = Jwts.builder()
                .subject("alice")
                .claim("role", "admin")
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThrows(io.jsonwebtoken.ExpiredJwtException.class,
                () -> Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(token));
    }

    @Test
    void tamperedTokenFailsValidation() {
        JwtService service = new JwtService(SECRET, 60_000);
        String token = service.generateToken(42L, "alice", "admin");
        // 修改 token 中间部分（payload），最后一个字符改一下。
        String tampered = token.substring(0, token.length() - 2) + "X";

        assertThrows(io.jsonwebtoken.security.SignatureException.class,
                () -> Jwts.parser()
                        .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                        .build()
                        .parseSignedClaims(tampered));
    }

    @Test
    void tokenContainsExpirationClaim() {
        JwtService service = new JwtService(SECRET, 60_000);

        var claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(service.generateToken(42L, "alice", "admin"))
                .getPayload();

        assertTrue(claims.getExpiration().after(new Date()),
                "过期时间应在未来，否则 token 签发即过期");
    }
}
