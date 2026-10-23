package com.aetheris.serving.service;

import com.aetheris.serving.dto.ApiKeyApplicationRequest;
import com.aetheris.serving.dto.ApiKeyCreateRequest;
import com.aetheris.serving.model.ApiKeyApplication;
import com.aetheris.serving.model.IssuedApiKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.serving.security.ServingSecurity;

@Service
public class ApiKeyApplicationService {
    private static final String SELECT = "SELECT id, application_name, requester, justification, quota_per_minute, expires_at, status, reviewed_by, reviewed_at, review_comment, created_at FROM serving.service_api_key_application ";
    private final JdbcTemplate jdbc;
    private final ApiKeyService apiKeys;

    public ApiKeyApplicationService(JdbcTemplate jdbc, ApiKeyService apiKeys) {
        this.jdbc = jdbc;
        this.apiKeys = apiKeys;
    }

    @Deprecated
    public ApiKeyApplication request(ApiKeyApplicationRequest input, String requester) {
        throw new ServingSecurity.UnauthorizedException("禁止仅凭用户名创建 API key 申请");
    }
    public ApiKeyApplication request(ApiKeyApplicationRequest input,TrustedUser user) {
        var owner=apiKeys.current(user);
        if (input.expiresAt() != null && !input.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("申请的过期时间必须晚于当前时间");
        }
        return apiKeys.transaction().execute(status->{Long id = jdbc.queryForObject("""
                INSERT INTO serving.service_api_key_application
                    (application_name, requester,requester_uid,requester_access_version, justification, quota_per_minute, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, input.name().trim(), owner.username(),owner.uid(),owner.accessVersion(),input.justification().trim(),
                input.effectiveQuota(), input.expiresAt());
        return find(id);});
    }

    public List<ApiKeyApplication> list(Optional<String> requester) {
        if(requester.isPresent())throw new ServingSecurity.UnauthorizedException("禁止按用户名替代稳定身份");
        return jdbc.query(SELECT+"ORDER BY created_at DESC",(rs,row)->map(rs));
    }
    public List<ApiKeyApplication> mine(long uid){if(uid<=0)throw new ServingSecurity.UnauthorizedException("身份无效");return jdbc.query(SELECT+"WHERE requester_uid=? ORDER BY created_at DESC",(rs,row)->map(rs),uid);}

    public IssuedApiKey approve(long id, String reviewer) {
        ApiKeyOwner original=owner(id,false);apiKeys.requireActive(original);
        return apiKeys.transaction().execute(status->{
        if(!original.equals(owner(id,true)))throw new ConflictException("申请身份已发生变化");
        ApiKeyApplication application = find(id);
        if (!"requested".equals(application.status())) throw new ConflictException("只能审批待处理的 API key 申请");
        int updated = jdbc.update("""
                UPDATE serving.service_api_key_application
                   SET status='approved', reviewed_by=?, reviewed_at=now()
                 WHERE id=? AND status='requested'
                """, reviewer, id);
        if (updated != 1) throw new ConflictException("API key 申请状态已发生变化");
        return apiKeys.createBound(new ApiKeyCreateRequest(application.name(), application.quotaPerMinute(), application.expiresAt()), original, reviewer);
        });
    }
    private ApiKeyOwner owner(long id,boolean lock){return jdbc.query("SELECT requester_uid,requester,requester_access_version FROM serving.service_api_key_application WHERE id=?"+(lock?" FOR UPDATE":""),(rs,row)->ApiKeyService.bound(rs,"requester_uid","requester","requester_access_version"),id).stream().findFirst().orElseThrow(()->new NotFoundException("API key 申请不存在"));}

    @Transactional
    public ApiKeyApplication reject(long id, String reviewer, String comment) {
        find(id);
        int updated = jdbc.update("""
                UPDATE serving.service_api_key_application
                   SET status='rejected', reviewed_by=?, reviewed_at=now(), review_comment=?
                 WHERE id=? AND status='requested'
                """, reviewer, comment == null ? null : comment.trim(), id);
        if (updated != 1) throw new ConflictException("只能驳回待处理的 API key 申请");
        return find(id);
    }

    public ApiKeyApplication find(long id) {
        return jdbc.query(SELECT + "WHERE id=?", (rs, row) -> map(rs), id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("API key 申请不存在"));
    }

    private ApiKeyApplication map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiKeyApplication(rs.getLong("id"), rs.getString("application_name"), rs.getString("requester"),
                rs.getString("justification"), rs.getInt("quota_per_minute"), rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"), rs.getString("reviewed_by"), rs.getObject("reviewed_at", OffsetDateTime.class),
                rs.getString("review_comment"), rs.getObject("created_at", OffsetDateTime.class));
    }

    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
    public static class ConflictException extends RuntimeException { public ConflictException(String message) { super(message); } }
}
