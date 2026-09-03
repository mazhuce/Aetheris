package com.aetheris.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 权限审计事件写入请求（与 auth-service PermissionEventRequest 协议对齐）。
 * 内网端点接收，只承载主体与决策要素，不含任何凭据或敏感数据。
 */
public record PermissionEventRequest(
        @Size(max = 64) String username,
        Long userId,
        @Size(max = 64) String resourceType,
        @Size(max = 64) String resourceId,
        @Size(max = 64) String action,
        @NotBlank @Size(max = 32)
        @Pattern(regexp = "ALLOWED|DENIED|GRANTED|REVOKED|UPDATED|DELETED|UNAVAILABLE")
        String decision,
        @Size(max = 512) String reason,
        @Size(max = 64) String requestId) {
}