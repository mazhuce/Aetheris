package com.aetheris.serving.service;

import com.aetheris.serving.dto.ApiKeyCreateRequest;
import com.aetheris.serving.model.ApiKeySummary;
import com.aetheris.serving.model.IssuedApiKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ApiKeyService {
    private static final String SELECT = "SELECT id, key_prefix, key_name, owner_username, status, quota_per_minute, expires_at, last_used_at, created_at, revoked_at FROM serving.service_api_key ";
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ApiKeySummary> list() {
        return jdbc.query(SELECT + "ORDER BY created_at DESC", (rs, row) -> map(rs));
    }

    @Transactional
    public IssuedApiKey create(ApiKeyCreateRequest input, String owner, String createdBy) {
        if (input.expiresAt() != null && !input.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("API key 过期时间必须晚于当前时间");
        }
        String randomPart = randomPart();
        String prefix = randomPart.substring(0, 12);
        String secret = "ak_" + prefix + "_" + randomPart.substring(12);
        long id = jdbc.queryForObject("""
                INSERT INTO serving.service_api_key
                    (key_prefix, key_hash, key_name, owner_username, quota_per_minute, expires_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, prefix, hash(secret), input.name().trim(), owner,
                input.effectiveQuota(), input.expiresAt(), createdBy);
        return new IssuedApiKey(find(id), secret);
    }

    @Transactional
    public IssuedApiKey rotate(long id, String actor) {
        ApiKeySummary old = find(id);
        if (!"active".equals(old.status())) throw new ConflictException("只能轮换有效 API key");
        jdbc.update("UPDATE serving.service_api_key SET status='revoked', revoked_at=now() WHERE id=? AND status='active'", id);
        return create(new ApiKeyCreateRequest(old.name(), old.quotaPerMinute(), old.expiresAt()), old.ownerUsername(), actor);
    }

    public ApiKeySummary revoke(long id) {
        int updated = jdbc.update("UPDATE serving.service_api_key SET status='revoked', revoked_at=now() WHERE id=? AND status='active'", id);
        if (updated == 0) throw new NotFoundException("有效 API key 不存在");
        return find(id);
    }

    public Optional<ApiKeyPrincipal> authenticate(String secret) {
        if (secret == null || !secret.startsWith("ak_") || secret.length() < 20) return Optional.empty();
        int separator = secret.indexOf('_', 3);
        if (separator <= 3 || separator + 1 >= secret.length()) return Optional.empty();
        String prefix = secret.substring(3, separator);
        List<ApiKeyPrincipal> principals = jdbc.query("""
                SELECT id, owner_username, key_hash, quota_per_minute
                  FROM serving.service_api_key
                 WHERE key_prefix=? AND status='active'
                   AND (expires_at IS NULL OR expires_at > now())
                """, (rs, row) -> new ApiKeyPrincipal(rs.getLong("id"), rs.getString("owner_username"), rs.getString("key_hash"), rs.getInt("quota_per_minute")), prefix);
        if (principals.isEmpty()) return Optional.empty();
        ApiKeyPrincipal principal = principals.get(0);
        if (!MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII), principal.hash().getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        return Optional.of(principal);
    }

    public boolean consumeQuota(long id) {
        int updated = jdbc.update("""
            UPDATE serving.service_api_key
               SET quota_window_start = CASE WHEN quota_window_start IS NULL OR quota_window_start < now() - INTERVAL '1 minute' THEN now() ELSE quota_window_start END,
                   quota_used = CASE WHEN quota_window_start IS NULL OR quota_window_start < now() - INTERVAL '1 minute' THEN 1 ELSE quota_used + 1 END,
                   last_used_at = now()
             WHERE id=? AND status='active' AND (expires_at IS NULL OR expires_at > now())
               AND (quota_window_start IS NULL OR quota_window_start < now() - INTERVAL '1 minute' OR quota_used < quota_per_minute)
            """, id);
        return updated == 1;
    }

    public ApiKeySummary find(long id) {
        return jdbc.query(SELECT + "WHERE id=?", (rs, row) -> map(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("API key 不存在"));
    }

    private ApiKeySummary map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiKeySummary(rs.getLong("id"), rs.getString("key_prefix"), rs.getString("key_name"),
                rs.getString("owner_username"), rs.getString("status"), rs.getInt("quota_per_minute"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("last_used_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("revoked_at", OffsetDateTime.class));
    }

    private String randomPart() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) result.append(String.format("%02x", b));
        return result.toString();
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("无法初始化 API key 摘要", error); }
    }

    public record ApiKeyPrincipal(long id, String ownerUsername, String hash, int quotaPerMinute) { }
    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
    public static class ConflictException extends RuntimeException { public ConflictException(String message) { super(message); } }
}
