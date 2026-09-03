package com.aetheris.serving.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

public record DatasetInput(
        @NotBlank String name,
        String description,
        String sourceType,
        Long datasourceId,
        String datasourceName,
        String querySql,
        String metricCode,
        JsonNode metricQuery,
        @Min(5) @Max(3600) Integer refreshSeconds) {
}
