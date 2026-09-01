package com.aetheris.serving.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record Dashboard(
        Long id,
        String name,
        String description,
        String slug,
        String status,
        JsonNode draftSpec,
        JsonNode publishedSpec,
        Integer publishedVersion,
        String createdBy,
        String updatedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt) {
}
