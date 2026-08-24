package com.aetheris.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AuditLogRequest(
        @NotBlank String username,
        @NotBlank String userRole,
        @NotBlank String method,
        @NotBlank String path,
        @NotNull @PositiveOrZero Integer statusCode,
        @NotNull @PositiveOrZero Long durationMs,
        String clientIp) {
}
