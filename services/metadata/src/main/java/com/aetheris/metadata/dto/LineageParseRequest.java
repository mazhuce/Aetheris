package com.aetheris.metadata.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * SQL 血缘解析请求。
 *
 * @param sql          待解析的 SQL，可含注释与多条语句
 * @param relationType 关系类型标签，例如 etl / aggregate；为空时按 etl 记录
 */
public record LineageParseRequest(
        @NotBlank(message = "SQL 不能为空") String sql,
        String relationType) {
}
