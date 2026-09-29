package com.aetheris.serving.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

/** PostgreSQL-backed fixed-window limiter shared by serving replicas. */
@Service
public class DistributedApiRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(DistributedApiRateLimiter.class);
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final int capacity;
    private final long windowMs;

    public DistributedApiRateLimiter(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            @Value("${aetheris.rate-limit.capacity:60}") int capacity,
            @Value("${aetheris.rate-limit.window-ms:60000}") long windowMs) {
        if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("分布式限流容量必须在 1 到 10000 之间");
        if (windowMs < 1000 || windowMs > 3600000) throw new IllegalArgumentException("分布式限流窗口必须在 1 秒到 1 小时之间");
        this.jdbc = jdbc;
        this.meters = meters;
        this.capacity = capacity;
        this.windowMs = windowMs;
    }

    public boolean allow(String subject, String assetCode) {
        long now = System.currentTimeMillis();
        long windowStartMs = (now / windowMs) * windowMs;
        Integer count = jdbc.queryForObject("""
            INSERT INTO serving.service_api_rate_window
                (subject_hash, asset_code, window_start, request_count, updated_at)
            VALUES (?, ?, ?, 1, now())
            ON CONFLICT (subject_hash, asset_code) DO UPDATE
                SET request_count = CASE
                        WHEN service_api_rate_window.window_start < EXCLUDED.window_start THEN 1
                        ELSE LEAST(service_api_rate_window.request_count + 1, ?)
                    END,
                    window_start = CASE
                        WHEN service_api_rate_window.window_start < EXCLUDED.window_start THEN EXCLUDED.window_start
                        ELSE service_api_rate_window.window_start
                    END,
                    updated_at = now()
            RETURNING request_count
            """, Integer.class, digest(subject), assetCode, Timestamp.from(Instant.ofEpochMilli(windowStartMs)), capacity + 1);
        boolean allowed = count != null && count <= capacity;
        meters.counter("aetheris.api.asset.distributed_limit", "outcome", allowed ? "allowed" : "limited").increment();
        return allowed;
    }

    @Scheduled(fixedDelayString = "${aetheris.rate-limit.cleanup-ms:300000}")
    public void cleanup() {
        try {
            jdbc.update("DELETE FROM serving.service_api_rate_window WHERE updated_at < now() - INTERVAL '2 hours'");
        } catch (RuntimeException failure) {
            log.warn("distributed API rate window cleanup failed", failure);
        }
    }

    private static String digest(String value) {
        String source = value == null ? "unknown" : value;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b));
            return result.toString();
        } catch (Exception error) { throw new IllegalStateException("无法初始化限流主体摘要", error); }
    }
}
