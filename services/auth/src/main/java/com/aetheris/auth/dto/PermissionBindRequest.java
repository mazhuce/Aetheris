package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 角色权限绑定请求：以权限目录 code 全量替换。 */
public record PermissionBindRequest(
        @NotNull List<String> codes) {
}