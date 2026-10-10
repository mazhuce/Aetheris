package com.aetheris.auth.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;
    private final long refreshExpirationMs;

    /** Compatibility constructor for focused unit tests and non-Spring callers. */
    public JwtService(String secret, long expirationMs) {
        this(secret, expirationMs, 604_800_000L);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms:900000}") long expirationMs,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32 || "change-me-to-a-long-random-secret".equals(secret)) {
            throw new IllegalStateException("JWT_SECRET must be a unique random value of at least 32 UTF-8 bytes");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /**
     * 签发登录令牌：sub=username，uid=数据库主键 id。
     * uid 是权限判定的稳定主体标识（决策引擎以数据库里的授权/角色/组为准），
     * username 仅用于兼容展示；两者都由网关从同一签名的 JWT 解析后转发，
     * 下游服务不得信任客户端自带的可伪造请求头。
     */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("uid", userId)
                .claim("role", role)
                .claim("typ", "access")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expirationMs))
                .signWith(key)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    /** Refresh tokens are signed JWTs with a distinct type and session version.
     * The auth service rotates the session version on every successful refresh,
     * making a replayed refresh token fail closed without storing raw tokens. */
    public String generateRefreshToken(Long userId, String username, long sessionVersion) {
        Date now = new Date();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("uid", userId)
                .claim("sv", sessionVersion)
                .claim("typ", "refresh")
                .issuedAt(now)
                .expiration(new Date(now.getTime() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    public Claims parseRefreshToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .require("typ", "refresh")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }
}
