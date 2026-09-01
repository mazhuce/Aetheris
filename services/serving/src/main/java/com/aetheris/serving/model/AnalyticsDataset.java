package com.aetheris.serving.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record AnalyticsDataset(
        Long id,
        String name,
        String description,
        Long datasourceId,
        String datasourceName,
        String querySql,
        JsonNode schema,
        Integer refreshSeconds,
        String status,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
