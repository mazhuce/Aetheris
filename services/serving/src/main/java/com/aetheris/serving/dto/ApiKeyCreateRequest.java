package com.aetheris.serving.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ApiKeyCreateRequest(
        @NotBlank @Size(max = 128) String name,
        @Min(1) @Max(10000) Integer quotaPerMinute,
        OffsetDateTime expiresAt) {
    public int effectiveQuota() { return quotaPerMinute == null ? 60 : quotaPerMinute; }
}
