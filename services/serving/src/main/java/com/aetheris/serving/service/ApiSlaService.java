package com.aetheris.serving.service;

import com.aetheris.serving.dto.ApiSlaPolicyRequest;
import com.aetheris.serving.model.ApiSlaEvaluation;
import com.aetheris.serving.model.ApiSlaPolicy;
import com.aetheris.serving.model.ApiUsageSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ApiSlaService {
    private static final String SELECT = "SELECT asset_code, availability_target, p95_target_ms, window_days, status, created_by, created_at, updated_by, updated_at FROM serving.service_api_sla_policy ";
    private final JdbcTemplate jdbc;
    private final ApiUsageService usage;

    public ApiSlaService(JdbcTemplate jdbc, ApiUsageService usage) { this.jdbc = jdbc; this.usage = usage; }

    public List<ApiSlaPolicy> list() { return jdbc.query(SELECT + "ORDER BY asset_code", (rs, row) -> map(rs)); }

    @Transactional
    public ApiSlaPolicy upsert(String assetCode, ApiSlaPolicyRequest input, String operator) {
        validateAssetCode(assetCode);
        BigDecimal availability = input.effectiveAvailability();
        jdbc.update("""
            INSERT INTO serving.service_api_sla_policy
                (asset_code, availability_target, p95_target_ms, window_days, status, created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (asset_code) DO UPDATE SET
                availability_target=EXCLUDED.availability_target,
                p95_target_ms=EXCLUDED.p95_target_ms,
                window_days=EXCLUDED.window_days,
                status=EXCLUDED.status,
                updated_by=EXCLUDED.updated_by,
                updated_at=now()
            """, assetCode, availability, input.effectiveP95(), input.effectiveWindowDays(), input.isPaused() ? "paused" : "active", operator, operator);
        return find(assetCode);
    }

    public ApiSlaEvaluation evaluate(String assetCode, OffsetDateTime from, OffsetDateTime to) {
        ApiSlaPolicy policy = find(assetCode);
        OffsetDateTime end = to == null ? OffsetDateTime.now() : to;
        OffsetDateTime start = from == null ? end.minusDays(policy.windowDays()) : from;
        if (!start.isBefore(end)) throw new IllegalArgumentException("SLA 评估时间范围无效");
        ApiUsageSummary summary = usage.summary(assetCode, start, end, 1).stream().findFirst()
                .orElse(new ApiUsageSummary(assetCode, 0, 0, 0, 1.0, 0, 0, null));
        double allowedFailures = summary.requests() * (1.0 - policy.availabilityTarget().doubleValue());
        double remaining = Math.max(0, allowedFailures - summary.failures());
        boolean availabilityOk = summary.requests() == 0 || summary.successRate() >= policy.availabilityTarget().doubleValue();
        boolean p95Ok = summary.requests() == 0 || summary.p95DurationMs() <= policy.p95TargetMs();
        return new ApiSlaEvaluation(policy, start, end, summary.requests(), summary.failures(), summary.successRate(), summary.p95DurationMs(), allowedFailures, remaining, availabilityOk, p95Ok, availabilityOk && p95Ok && "active".equals(policy.status()));
    }

    public ApiSlaPolicy find(String assetCode) {
        validateAssetCode(assetCode);
        return jdbc.query(SELECT + "WHERE asset_code=?", (rs, row) -> map(rs), assetCode).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("API 资产 SLA 策略不存在"));
    }

    private ApiSlaPolicy map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApiSlaPolicy(rs.getString("asset_code"), rs.getBigDecimal("availability_target"), rs.getInt("p95_target_ms"), rs.getInt("window_days"), rs.getString("status"), rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class), rs.getString("updated_by"), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static void validateAssetCode(String assetCode) {
        if (assetCode == null || !assetCode.matches("[A-Za-z][A-Za-z0-9_]{1,63}")) throw new IllegalArgumentException("API 资产编码格式无效");
    }

    public static class NotFoundException extends RuntimeException { public NotFoundException(String message) { super(message); } }
}
