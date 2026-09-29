package com.aetheris.serving.model;

import java.time.OffsetDateTime;

public record ApiKeySummary(
        Long id,
        String keyPrefix,
        String name,
        String ownerUsername,
        String status,
        int quotaPerMinute,
        OffsetDateTime expiresAt,
        OffsetDateTime lastUsedAt,
        OffsetDateTime createdAt,
        OffsetDateTime revokedAt) { }
