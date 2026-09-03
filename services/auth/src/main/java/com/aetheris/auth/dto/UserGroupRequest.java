package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 用户组创建/更新请求。 */
public record UserGroupRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description) {
}