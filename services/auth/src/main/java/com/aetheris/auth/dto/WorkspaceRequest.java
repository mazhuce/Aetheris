package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 项目空间创建/更新请求。 */
public record WorkspaceRequest(
        @NotNull Long organizationId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @NotBlank @Pattern(regexp = "active|disabled") String status) {
}