package com.aetheris.serving.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

public record ApiSlaPolicyRequest(
        @DecimalMin("0.95") @DecimalMax("0.999999") BigDecimal availabilityTarget,
        @Min(1) @Max(300000) Integer p95TargetMs,
        @Min(1) @Max(365) Integer windowDays,
        Boolean paused) {
    public BigDecimal effectiveAvailability() { return availabilityTarget == null ? new BigDecimal("0.99") : availabilityTarget; }
    public int effectiveP95() { return p95TargetMs == null ? 1000 : p95TargetMs; }
    public int effectiveWindowDays() { return windowDays == null ? 30 : windowDays; }
    public boolean isPaused() { return Boolean.TRUE.equals(paused); }
}
