package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 用户角色绑定请求：以角色 code 全量替换。 */
public record UserRoleBindRequest(
        @NotNull List<String> roleCodes) {
}