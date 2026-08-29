package com.aetheris.metadata.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 内部 SQL 执行请求（供 workflow 服务调用）。
 *
 * @param sql            待执行的写入语句，由 SqlWriteGuard 校验
 * @param timeoutSeconds 语句超时秒数；为空时用服务端默认的写入超时
 */
public record SqlExecuteRequest(
        @NotBlank(message = "SQL 不能为空") String sql,
        Integer timeoutSeconds) {
}
