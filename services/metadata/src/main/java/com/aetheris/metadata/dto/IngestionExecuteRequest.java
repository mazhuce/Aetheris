package com.aetheris.metadata.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record IngestionExecuteRequest(
        @NotNull Long sourceDatasourceId,
        @NotNull Long targetDatasourceId,
        @NotBlank String sourceSql,
        @NotBlank String targetTable,
        @Pattern(regexp = "append|replace") String writeMode,
        @Min(1) @Max(5000) Integer batchSize) {
}
