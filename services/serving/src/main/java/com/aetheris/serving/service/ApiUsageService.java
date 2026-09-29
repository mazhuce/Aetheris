package com.aetheris.serving.service;

import com.aetheris.serving.model.ApiUsageSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class ApiUsageService {
    private static final Logger log = LoggerFactory.getLogger(ApiUsageService.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public ApiUsageService(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** Usage telemetry is fail-open: a metrics database issue must not break an authorized API call. */
    public void record(String assetCode, String principal, String authType, int statusCode, long durationMs, String errorCode) {
        String safeAsset = assetCode == null ? "unknown" : assetCode;
        String safeAuth = "api_key".equals(authType) ? "api_key" : "identity";
        long safeDuration = Math.max(0, Math.min(durationMs, Integer.MAX_VALUE));
        meters.counter("aetheris.api.asset.requests", "auth", safeAuth, "outcome", statusCode < 400 ? "success" : "failure").increment();
        meters.timer("aetheris.api.asset.duration", "auth", safeAuth).record(java.time.Duration.ofMillis(safeDuration));
        try {
            jdbc.update("""
                INSERT INTO serving.service_api_usage
                    (asset_code, principal_hash, auth_type, status_code, duration_ms, error_code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, safeAsset, digest(principal), safeAuth, statusCode, safeDuration, normalizeError(errorCode));
        } catch (RuntimeException failure) {
            log.warn("API usage telemetry write failed for asset {}", safeAsset, failure);
        }
    }

    public List<ApiUsageSummary> summary(String assetCode, OffsetDateTime from, OffsetDateTime to, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        String filter = assetCode == null || assetCode.isBlank() ? "" : " AND asset_code = ?";
        String sql = """
            SELECT asset_code,
                   COUNT(*) AS requests,
                   COUNT(*) FILTER (WHERE status_code < 400) AS successes,
                   COUNT(*) FILTER (WHERE status_code >= 400) AS failures,
                   AVG(duration_ms) AS average_duration_ms,
                   PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_duration_ms,
                   MAX(created_at) AS last_request_at
              FROM serving.service_api_usage
             WHERE created_at >= ? AND created_at < ?
            """ + filter + " GROUP BY asset_code ORDER BY requests DESC LIMIT ?";
        List<Object> args = new java.util.ArrayList<>();
        args.add(from == null ? OffsetDateTime.now().minusDays(1) : from);
        args.add(to == null ? OffsetDateTime.now() : to);
        if (!filter.isEmpty()) args.add(assetCode);
        args.add(boundedLimit);
        return jdbc.query(sql, (rs, row) -> {
            long requests = rs.getLong("requests");
            long successes = rs.getLong("successes");
            return new ApiUsageSummary(rs.getString("asset_code"), requests, successes, rs.getLong("failures"),
                    requests == 0 ? 0 : ((double) successes / requests), rs.getDouble("average_duration_ms"),
                    rs.getDouble("p95_duration_ms"), rs.getObject("last_request_at", OffsetDateTime.class));
        }, args.toArray());
    }

    @Scheduled(fixedDelayString = "${aetheris.api-usage.cleanup-ms:3600000}")
    public void cleanup() {
        try {
            jdbc.update("DELETE FROM serving.service_api_usage WHERE created_at < now() - INTERVAL '90 days'");
        } catch (RuntimeException failure) {
            log.warn("API usage ledger cleanup failed", failure);
        }
    }

    private static String digest(String value) {
        String source = value == null ? "unknown" : value;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("无法初始化调用主体摘要", error); }
    }

    private static String normalizeError(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return null;
        return errorCode.length() <= 64 ? errorCode : errorCode.substring(0, 64);
    }
}
