package com.aetheris.serving.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Unified safe request body for a published metric/table API asset. */
public record ApiAssetQueryRequest(
        @Size(max = 8) List<String> dimensions,
        String timeGrain,
        String startDate,
        String endDate,
        @Size(max = 50) List<String> columns,
        @Min(1) @Max(100) Integer limit) {
    public ApiAssetQueryRequest {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
