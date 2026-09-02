package com.aetheris.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class TokenRevocationStore {

    private final Cache<String, Instant> revoked = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfter(new Expiry<String, Instant>() {
                @Override
                public long expireAfterCreate(String key, Instant value, long currentTime) {
                    return Math.max(1, Duration.between(Instant.now(), value).toNanos());
                }

                @Override
                public long expireAfterUpdate(String key, Instant value, long currentTime, long currentDuration) {
                    return expireAfterCreate(key, value, currentTime);
                }

                @Override
                public long expireAfterRead(String key, Instant value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    public void revoke(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return;
        }
        long ttlMillis = expiresAt.toEpochMilli() - System.currentTimeMillis();
        if (ttlMillis > 0) {
            revoked.put(jti, expiresAt);
        }
    }

    public boolean isRevoked(String jti) {
        Instant expiresAt = jti == null ? null : revoked.getIfPresent(jti);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    public static String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return "legacy:" + result;
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
