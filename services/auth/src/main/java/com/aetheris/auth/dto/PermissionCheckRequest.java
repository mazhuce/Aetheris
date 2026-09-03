package com.aetheris.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 服务间权限判定请求（内部协议）。
 *
 * <p>ctx 为数据范围上下文，供祖先授权与 data_scope 匹配使用，键取值：
 * domainId / domainCode / tableId / fieldId / metricId / workspaceId /
 * datasetId / dashboardId。ctx 由受控服务端上下文构建，绝不由浏览器请求头填充。
 */
public record PermissionCheckRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 32) String resourceType,
        Long resourceId,
        @NotBlank @Size(max = 32) String action,
        Map<String, String> ctx) {
}