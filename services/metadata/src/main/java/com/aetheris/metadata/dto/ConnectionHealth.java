package com.aetheris.metadata.dto;

import java.time.OffsetDateTime;

/** A recent SELECT 1 observation, independent of the datasource's configured status. */
public record ConnectionHealth(String status, OffsetDateTime checkedAt, OffsetDateTime lastSuccessAt,
                               OffsetDateTime expiresAt, Long latencyMs,
                               String failureCode, String failureMessage) {
    public static ConnectionHealth unknown() {
        return new ConnectionHealth("unknown", null, null, null, null, null, null);
    }
}
