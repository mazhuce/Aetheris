package com.aetheris.serving.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DashboardUpdateRequest(
        @NotBlank String name,
        String description,
        @NotNull JsonNode spec) {
}
