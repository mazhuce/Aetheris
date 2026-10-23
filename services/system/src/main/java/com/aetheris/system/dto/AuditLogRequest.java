package com.aetheris.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AuditLogRequest(
        @NotBlank @jakarta.validation.constraints.Size(max=64) String username,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp="admin|viewer|anonymous|unknown|sys_admin|sys_viewer|api_key") String userRole,
        @NotBlank @jakarta.validation.constraints.Pattern(regexp="GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|CONNECT|TRACE") String method,
        @NotBlank @jakarta.validation.constraints.Size(max=512) @jakarta.validation.constraints.Pattern(regexp="/[^\\p{Cntrl}]*") String path,
        @NotNull @jakarta.validation.constraints.Min(100) @jakarta.validation.constraints.Max(599) Integer statusCode,
        @NotNull @PositiveOrZero Long durationMs,
        @jakarta.validation.constraints.Size(max=64) @jakarta.validation.constraints.Pattern(regexp="[^\\p{Cntrl}]*") String clientIp) {
}
