package com.aetheris.serving.service;

import com.aetheris.serving.dto.ApiKeyCreateRequest;
import com.aetheris.serving.model.ApiKeySummary;
import com.aetheris.serving.model.IssuedApiKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.serving.security.ServingSecurity;

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
    private final ApiKeyOwnerValidator owners;

    @Autowired public ApiKeyService(JdbcTemplate jdbc,ApiKeyOwnerValidator owners) { this.jdbc = jdbc;this.owners=owners; }
    public ApiKeyService(JdbcTemplate jdbc) { this(jdbc,null); }
    TransactionTemplate transaction(){return new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));}
    ApiKeyOwner current(TrustedUser user){if(owners==null)throw ApiKeyOwnerValidator.unavailable();return owners.current(user);}
    void requireActive(ApiKeyOwner owner){if(owners==null)throw ApiKeyOwnerValidator.unavailable();owners.requireActive(owner);}

    public List<ApiKeySummary> list() {
        return jdbc.query(SELECT + "ORDER BY created_at DESC", (rs, row) -> map(rs));
    }

    @Deprecated
    public IssuedApiKey create(ApiKeyCreateRequest input, String owner, String createdBy) {
        throw new ServingSecurity.UnauthorizedException("禁止仅凭用户名签发 API key");
    }
    public IssuedApiKey create(ApiKeyCreateRequest input, TrustedUser user, String createdBy) {
        var owner=current(user);
        return transaction().execute(status->createBound(input,owner,createdBy));
    }
    IssuedApiKey createBound(ApiKeyCreateRequest input,ApiKeyOwner owner,String createdBy) {
        if (input.expiresAt() != null && !input.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("API key 过期时间必须晚于当前时间");
        }
        String randomPart = randomPart();
        String prefix = randomPart.substring(0, 12);
        String secret = "ak_" + prefix + "_" + randomPart.substring(12);
        long id = jdbc.queryForObject("""
                INSERT INTO serving.service_api_key
                    (key_prefix, key_hash, key_name, owner_username, owner_uid, owner_access_version, quota_per_minute, expires_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, prefix, hash(secret), input.name().trim(), owner.username(),owner.uid(),owner.accessVersion(),
                input.effectiveQuota(), input.expiresAt(), createdBy);
        return new IssuedApiKey(find(id), secret);
    }

    public IssuedApiKey rotate(long id, String actor) {
        var owner=owner(id,false);requireActive(owner);
        return transaction().execute(status->{
            if(!owner.equals(owner(id,true)))throw new ConflictException("API key 身份已发生变化");
            ApiKeySummary old=find(id);
            if(!"active".equals(old.status()))throw new ConflictException("只能轮换有效 API key");
            if(jdbc.update("UPDATE serving.service_api_key SET status='revoked',revoked_at=clock_timestamp() WHERE id=? AND status='active'",id)!=1)throw new ConflictException("API key 状态已发生变化");
            return createBound(new ApiKeyCreateRequest(old.name(),old.quotaPerMinute(),old.expiresAt()),owner,actor);
        });
    }
    private ApiKeyOwner owner(long id,boolean lock){return jdbc.query("SELECT owner_uid,owner_username,owner_access_version FROM serving.service_api_key WHERE id=?"+(lock?" FOR UPDATE":""),(r,i)->bound(r,"owner_uid","owner_username","owner_access_version"),id).stream().findFirst().orElseThrow(()->new NotFoundException("API key 不存在"));}
    static ApiKeyOwner bound(java.sql.ResultSet r,String uid,String name,String av)throws java.sql.SQLException {
        Long id=r.getObject(uid,Long.class),version=r.getObject(av,Long.class);
        if(id==null||version==null)throw new ServingSecurity.UnauthorizedException("旧 API key 身份未绑定，请重新申请");
        return new ApiKeyOwner(id,r.getString(name),version);
    }

    public ApiKeySummary revoke(long id) {
        int updated = jdbc.update("UPDATE serving.service_api_key SET status='revoked', revoked_at=now() WHERE id=? AND status='active'", id);
        if (updated == 0) throw new NotFoundException("有效 API key 不存在");
        return find(id);
    }

    public Optional<ApiKeyPrincipal> authenticate(String secret) {
        return authenticate(secret,false);
    }
    Optional<ApiKeyPrincipal> authenticate(String secret,boolean lock) {
        if (secret == null || !secret.matches("ak_[a-f0-9]{12}_[a-f0-9]{52}")) return Optional.empty();
        int separator = secret.indexOf('_', 3);
        if (separator <= 3 || separator + 1 >= secret.length()) return Optional.empty();
        String prefix = secret.substring(3, separator);
        List<ApiKeyPrincipal> principals = jdbc.query("""
                SELECT id, owner_username,owner_uid,owner_access_version,key_hash,quota_per_minute
                  FROM serving.service_api_key
                 WHERE key_prefix=? AND status='active'
                   AND owner_uid IS NOT NULL AND owner_access_version IS NOT NULL
                   AND (expires_at IS NULL OR expires_at > clock_timestamp())
                """+(lock?" FOR UPDATE":""), (rs, row) -> new ApiKeyPrincipal(rs.getLong("id"), rs.getString("owner_username"), rs.getString("key_hash"), rs.getInt("quota_per_minute"),rs.getLong("owner_uid"),rs.getLong("owner_access_version")), prefix);
        if (principals.isEmpty()) return Optional.empty();
        ApiKeyPrincipal principal = principals.get(0);
        if (!MessageDigest.isEqual(hash(secret).getBytes(StandardCharsets.US_ASCII), principal.hash().getBytes(StandardCharsets.US_ASCII))) return Optional.empty();
        return Optional.of(principal);
    }

    public boolean consumeQuota(long id) {
        int updated = jdbc.update("""
            WITH moment AS MATERIALIZED (SELECT clock_timestamp() AS t)
            UPDATE serving.service_api_key
               SET quota_window_start = CASE WHEN quota_window_start IS NULL OR quota_window_start < moment.t - INTERVAL '1 minute' THEN moment.t ELSE quota_window_start END,
                   quota_used = CASE WHEN quota_window_start IS NULL OR quota_window_start < moment.t - INTERVAL '1 minute' THEN 1 ELSE quota_used + 1 END,
                   last_used_at = moment.t
              FROM moment
             WHERE id=? AND status='active' AND owner_uid IS NOT NULL AND owner_access_version IS NOT NULL AND (expires_at IS NULL OR expires_at > moment.t)
               AND (quota_window_start IS NULL OR quota_window_start < moment.t - INTERVAL '1 minute' OR quota_used < quota_per_minute)
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

    public record ApiKeyPrincipal(long id, String ownerUsername, String hash, int quotaPerMinute,Long ownerUid,Long ownerAccessVersion) {
        public ApiKeyPrincipal(long id,String username,String hash,int quota){this(id,username,hash,quota,null,null);}
        public ApiKeyOwner owner(){if(ownerUid==null||ownerAccessVersion==null)throw new ServingSecurity.UnauthorizedException("API key 身份未绑定");return new ApiKeyOwner(ownerUid,ownerUsername,ownerAccessVersion);}
        @Override public String toString(){return "ApiKeyPrincipal[redacted]";}
    }
    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
    public static class ConflictException extends RuntimeException { public ConflictException(String message) { super(message); } }
}
