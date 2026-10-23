package com.aetheris.system.dto;

import com.aetheris.system.entity.SystemConfig;
import java.time.OffsetDateTime;

/** Explicit management contract: never serialize a persistence entity directly. */
public record SystemConfigResponse(Long id, String configKey, String configValue, String description,
                                   OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    public static SystemConfigResponse from(SystemConfig config) {
        return new SystemConfigResponse(config.getId(), config.getConfigKey(), config.getConfigValue(),
                config.getDescription(), config.getCreatedAt(), config.getUpdatedAt());
    }
}
