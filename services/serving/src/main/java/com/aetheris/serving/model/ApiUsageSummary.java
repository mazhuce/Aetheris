package com.aetheris.serving.model;

import java.time.OffsetDateTime;

public record ApiUsageSummary(
        String assetCode,
        long requests,
        long successes,
        long failures,
        double successRate,
        double averageDurationMs,
        double p95DurationMs,
        OffsetDateTime lastRequestAt) { }
