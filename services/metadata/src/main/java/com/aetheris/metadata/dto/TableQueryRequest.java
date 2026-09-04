package com.aetheris.metadata.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/** Safe table-data contract: callers select known columns, never submit SQL. */
public record TableQueryRequest(List<String> columns, @Min(1) @Max(100) Integer limit) {
    public TableQueryRequest {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
