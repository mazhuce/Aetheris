package com.aetheris.metadata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 元数据采集请求。
 *
 * @param datasourceId 目标数据源 id
 * @param schemaName   指定库/Schema；为空表示采集全部非系统库
 */
public record MetadataSyncRequest(
        @NotNull Long datasourceId,
        @Size(max = 128) @Pattern(regexp = "|[A-Za-z0-9_$-]+", message = "库/Schema 包含不支持的字符")
        String schemaName) {
}
