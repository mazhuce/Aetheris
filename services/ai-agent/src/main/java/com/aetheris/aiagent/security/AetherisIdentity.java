package com.aetheris.aiagent.security;

/** 身份网关验签后缓存到当前请求的最小身份模型。 */
public record AetherisIdentity(Long uid, String username) {
}
