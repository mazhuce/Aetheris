package com.aetheris.serving.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ApiSlaPolicy(
        String assetCode,
        BigDecimal availabilityTarget,
        int p95TargetMs,
        int windowDays,
        String status,
        String createdBy,
        OffsetDateTime createdAt,
        String updatedBy,
        OffsetDateTime updatedAt) { }
