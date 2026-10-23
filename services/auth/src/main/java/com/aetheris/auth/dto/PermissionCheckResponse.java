package com.aetheris.auth.dto;

/** 服务间权限判定响应：allowed=false 时必须给出可读的拒绝原因。 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PermissionCheckResponse(boolean allowed, String reason) {
}
