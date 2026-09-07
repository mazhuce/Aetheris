package com.aetheris.metadata.entity;

import java.time.OffsetDateTime;

/** Internal persisted probe state; never deserialized from datasource request bodies. */
public record DataSourceHealthObservation(Long datasourceId, Long configurationRevision, String status,
                                          String probeToken, OffsetDateTime startedAt,
                                          OffsetDateTime checkedAt, OffsetDateTime lastSuccessAt,
                                          OffsetDateTime expiresAt, Long latencyMs,
                                          String failureCode, String failureMessage) {
}
