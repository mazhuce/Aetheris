package com.aetheris.serving.model;

import java.time.OffsetDateTime;

public record ApiKeyApplication(
        Long id,
        String name,
        String requester,
        String justification,
        int quotaPerMinute,
        OffsetDateTime expiresAt,
        String status,
        String reviewedBy,
        OffsetDateTime reviewedAt,
        String reviewComment,
        OffsetDateTime createdAt) { }
