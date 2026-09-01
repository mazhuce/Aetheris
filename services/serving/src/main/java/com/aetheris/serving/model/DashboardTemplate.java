package com.aetheris.serving.model;

import com.fasterxml.jackson.databind.JsonNode;

public record DashboardTemplate(
        Long id,
        String code,
        String name,
        String description,
        String category,
        String previewTone,
        JsonNode spec,
        Integer sortOrder) {
}
