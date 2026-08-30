package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改自己的密码。
 *
 * @param currentPassword 当前密码。必须校验：否则任何持有有效令牌的会话（例如未登出的
 *                        公共电脑、被盗用的令牌）都能直接改掉密码并锁死账号所有者。
 * @param newPassword     新密码，长度下限与管理端保持一致（12 位），避免两条路径策略不一
 */
public record ChangePasswordRequest(
        @NotBlank(message = "请输入当前密码") String currentPassword,
        @NotBlank(message = "请输入新密码")
        @Size(min = 12, max = 128, message = "新密码长度需为 12-128 位") String newPassword) {
}
