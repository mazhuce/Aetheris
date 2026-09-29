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

@Service
public class ApiKeyApplicationService {
    private static final String SELECT = "SELECT id, application_name, requester, justification, quota_per_minute, expires_at, status, reviewed_by, reviewed_at, review_comment, created_at FROM serving.service_api_key_application ";
    private final JdbcTemplate jdbc;
    private final ApiKeyService apiKeys;

    public ApiKeyApplicationService(JdbcTemplate jdbc, ApiKeyService apiKeys) {
        this.jdbc = jdbc;
        this.apiKeys = apiKeys;
    }

    @Transactional
    public ApiKeyApplication request(ApiKeyApplicationRequest input, String requester) {
        if (input.expiresAt() != null && !input.expiresAt().isAfter(OffsetDateTime.now())) {
            throw new IllegalArgumentException("申请的过期时间必须晚于当前时间");
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO serving.service_api_key_application
                    (application_name, requester, justification, quota_per_minute, expires_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, input.name().trim(), requester, input.justification().trim(),
                input.effectiveQuota(), input.expiresAt());
        return find(id);
    }

    public List<ApiKeyApplication> list(Optional<String> requester) {
        String suffix = requester.isPresent() ? "WHERE requester=? ORDER BY created_at DESC" : "ORDER BY created_at DESC";
        return requester.isPresent()
                ? jdbc.query(SELECT + suffix, (rs, row) -> map(rs), requester.get())
                : jdbc.query(SELECT + suffix, (rs, row) -> map(rs));
    }

    @Transactional
    public IssuedApiKey approve(long id, String reviewer) {
        ApiKeyApplication application = find(id);
        if (!"requested".equals(application.status())) throw new ConflictException("只能审批待处理的 API key 申请");
        int updated = jdbc.update("""
                UPDATE serving.service_api_key_application
                   SET status='approved', reviewed_by=?, reviewed_at=now()
                 WHERE id=? AND status='requested'
                """, reviewer, id);
        if (updated != 1) throw new ConflictException("API key 申请状态已发生变化");
        return apiKeys.create(new ApiKeyCreateRequest(application.name(), application.quotaPerMinute(), application.expiresAt()), application.requester(), reviewer);
    }

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
