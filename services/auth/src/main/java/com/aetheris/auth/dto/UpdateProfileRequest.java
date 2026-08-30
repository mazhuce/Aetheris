package com.aetheris.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 修改自己的资料。
 *
 * <p>刻意不包含 {@code role} 与 {@code status} 字段：若允许用户自行提交这两项，
 * viewer 就能把自己改成 admin，或把停用状态改回启用，等于绕过整套权限控制。
 * 角色与状态的变更只能走管理端 {@code /api/auth/users/{id}}，那条路径要求 admin 身份。
 *
 * <p>同样不含 {@code username}：用户名是登录标识与审计主键，变更会让历史审计日志
 * 无法关联到人。需要改名时应由管理员评估。
 */
public record UpdateProfileRequest(
        @Size(max = 64, message = "昵称最多 64 个字符") String nickname,
        @Email(message = "邮箱格式不正确") @Size(max = 128) String email,
        @Size(max = 32, message = "手机号最多 32 个字符") String phone) {
}
