package com.aetheris.serving.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record DataSubscription(
        Long id,
        String subscriptionName,
        String subscriber,
        String targetType,
        String targetName,
        String deliverType,
        String frequency,
        JsonNode config,
        String status,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
