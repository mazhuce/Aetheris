package com.aetheris.auth.service;

import com.aetheris.auth.validation.UsernameRules;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final long expirationMs;
    private final long refreshExpirationMs;

    public JwtService(String secret, long expirationMs) { this(secret, expirationMs, 604_800_000L); }

    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiration-ms:900000}") long expirationMs,
                      @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32 || "change-me-to-a-long-random-secret".equals(secret))
            throw new IllegalStateException("JWT_SECRET must be a unique random value of at least 32 UTF-8 bytes");
        if (expirationMs < 1000 || refreshExpirationMs < 1000)
            throw new IllegalStateException("Browser token lifetimes must be at least one second");
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public record BrowserCredential(UUID sid, long uid, String username, long accessVersion, long refreshVersion) { }

    public String generateToken(BrowserSessionService.Session session) {
        Instant accessExpiry = Instant.now().plusMillis(expirationMs);
        if (session.expiresAt().isBefore(accessExpiry)) accessExpiry = session.expiresAt();
        return token(session, "access", accessExpiry).claim("role", session.role() == null ? "viewer" : session.role())
                .signWith(key).compact();
    }

    public String generateRefreshToken(BrowserSessionService.Session session) {
        return token(session, "refresh", session.expiresAt()).claim("rv", session.refreshVersion()).signWith(key).compact();
    }

    /** Login-only capability. Rotation must never reissue it in a late refresh response. */
    public String generateLogoutToken(BrowserSessionService.Session session) {
        return token(session, "browser-logout", session.expiresAt()).signWith(key).compact();
    }

    public BrowserCredential parseRefreshToken(String token) { return credential(token, "refresh"); }
    public BrowserCredential parseLogoutToken(String token) { return credential(token, "browser-logout"); }

    private JwtBuilder token(BrowserSessionService.Session session, String type, Instant expiresAt) {
        if (session.sid() == null || session.uid() <= 0 || session.accessVersion() < 0 || session.refreshVersion() < 0)
            throw new IllegalArgumentException("Invalid browser session");
        UsernameRules.requireValid(session.username());
        return Jwts.builder().id(UUID.randomUUID().toString()).subject(session.username())
                .claim("uid", session.uid()).claim("av", session.accessVersion()).claim("sid", session.sid().toString())
                .claim("typ", type).issuedAt(new Date()).expiration(Date.from(expiresAt));
    }

    private BrowserCredential credential(String token, String type) {
        Claims claims = Jwts.parser().verifyWith(key).require("typ", type).build().parseSignedClaims(token).getPayload();
        String username = UsernameRules.requireValid(claims.getSubject());
        String sid = claims.get("sid", String.class);
        UUID family = sid == null ? null : UUID.fromString(sid);
        if (family == null || !family.toString().equals(sid) || claims.getExpiration() == null)
            throw new IllegalArgumentException("Invalid browser family");
        long uid = integer(claims, "uid"), av = integer(claims, "av");
        long version = "refresh".equals(type) ? integer(claims, "rv") : 0;
        if (uid <= 0 || av < 0 || version < 0) throw new IllegalArgumentException("Invalid browser subject");
        return new BrowserCredential(family, uid, username, av, version);
    }

    private static long integer(Claims claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Invalid browser claim");
        return ((Number) value).longValue();
    }

    public long getExpirationMs() { return expirationMs; }
    public long getRefreshExpirationMs() { return refreshExpirationMs; }
}
