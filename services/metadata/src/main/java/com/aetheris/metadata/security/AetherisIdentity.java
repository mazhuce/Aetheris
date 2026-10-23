package com.aetheris.metadata.security;

/**
 * TrustedUser导出的展示/审计视图；此record本身不是认证凭证。
 *
 * @param uid      稳定用户主键
 * @param username 已验证用户名
 */
public record AetherisIdentity(Long uid, String username) {
}