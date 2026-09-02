package com.aetheris.serving.model;

import java.time.OffsetDateTime;

public record ServiceGrant(
        Long id,
        String apiPath,
        String grantee,
        String grantType,
        String scope,
        OffsetDateTime expiresAt,
        String status,
        String grantedBy,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
