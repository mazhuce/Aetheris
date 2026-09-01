package com.aetheris.serving.dto;

import jakarta.validation.constraints.NotBlank;

public record DashboardCreateRequest(
        @NotBlank String name,
        String description,
        Long templateId) {
}
