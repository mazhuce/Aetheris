package com.aetheris.metadata.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/** 统一指标查询契约；消费方只提交业务筛选，不接触底层 SQL。 */
public record MetricQueryRequest(
        List<String> dimensions,
        String timeGrain,
        String startDate,
        String endDate,
        @Min(1) @Max(100) Integer limit) {

    public MetricQueryRequest {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }
}
