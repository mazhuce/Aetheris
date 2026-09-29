package com.aetheris.serving.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ApiSlaEvaluation(
        ApiSlaPolicy policy,
        OffsetDateTime from,
        OffsetDateTime to,
        long requests,
        long failures,
        double availability,
        double p95DurationMs,
        double allowedFailures,
        double remainingFailureBudget,
        boolean availabilityWithinTarget,
        boolean p95WithinTarget,
        boolean compliant) { }
