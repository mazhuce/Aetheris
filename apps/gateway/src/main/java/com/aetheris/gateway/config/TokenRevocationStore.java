package com.aetheris.gateway.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class TokenRevocationStore {

    private static final String KEY_PREFIX = "aetheris:jwt:revoked:";
    private final ReactiveStringRedisTemplate redis;

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

    public TokenRevocationStore(ObjectProvider<ReactiveStringRedisTemplate> redis) {
        this.redis = redis.getIfAvailable();
    }

    /** Unit-test constructor. Production always injects Redis through Spring. */
    TokenRevocationStore() {
        this.redis = null;
    }

    public Mono<Void> revoke(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            return Mono.empty();
        }
        long ttlMillis = expiresAt.toEpochMilli() - System.currentTimeMillis();
        if (ttlMillis <= 0) {
            return Mono.empty();
        }
        if (redis == null) {
            revoked.put(jti, expiresAt);
            return Mono.empty();
        }
        return redis.opsForValue()
                .set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMillis))
                .then();
    }

    public Mono<Boolean> isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return Mono.just(false);
        }
        if (redis != null) {
            return redis.hasKey(KEY_PREFIX + jti);
        }
        Instant expiresAt = jti == null ? null : revoked.getIfPresent(jti);
        return Mono.just(expiresAt != null && expiresAt.isAfter(Instant.now()));
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
