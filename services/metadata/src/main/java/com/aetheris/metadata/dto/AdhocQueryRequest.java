package com.aetheris.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 即席查询请求：面向分析人员的自助只读查询。
 */
public record AdhocQueryRequest(
        @NotNull Long datasourceId,
        @NotBlank @Size(max = 4000) String sql) {
}
