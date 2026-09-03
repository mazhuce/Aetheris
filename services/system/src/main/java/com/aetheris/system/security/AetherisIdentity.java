package com.aetheris.system.security;

/**
 * 网关认证后的身份（从 X-Aetheris-Identity 验签解出）。
 *
 * @param uid      用户主键 id（JWT uid claim，缺失时为 null）
 * @param username 用户名（JWT subject）
 */
public record AetherisIdentity(Long uid, String username) {
}