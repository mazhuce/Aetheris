package com.aetheris.serving.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DatasetInput(
        @NotBlank String name,
        String description,
        @NotNull Long datasourceId,
        String datasourceName,
        @NotBlank String querySql,
        @Min(5) @Max(3600) Integer refreshSeconds) {
}
