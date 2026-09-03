package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 自定义角色创建/更新请求。系统角色（sys_admin/sys_viewer）不允许修改。 */
public record RbacRoleRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z][a-z0-9_]*") String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description) {
}