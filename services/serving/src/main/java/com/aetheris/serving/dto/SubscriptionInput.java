package com.aetheris.serving.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SubscriptionInput(
        @NotBlank String name,
        @NotBlank String subscriber,
        @Pattern(regexp = "table|api|product|dataset") String targetType,
        @NotBlank String targetName,
        @Pattern(regexp = "api|file|stream|email") String deliverType,
        String frequency,
        JsonNode config,
        @Pattern(regexp = "active|paused|cancelled") String status) {
}
