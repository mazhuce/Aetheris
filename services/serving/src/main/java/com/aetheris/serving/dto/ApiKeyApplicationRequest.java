package com.aetheris.serving.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ApiKeyApplicationRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 1000) String justification,
        @Min(1) @Max(10000) Integer quotaPerMinute,
        OffsetDateTime expiresAt) {
    public int effectiveQuota() { return quotaPerMinute == null ? 60 : quotaPerMinute; }
}
