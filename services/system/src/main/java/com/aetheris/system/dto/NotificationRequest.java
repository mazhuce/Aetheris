package com.aetheris.system.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 内部通知写入请求：由 quality / ingestion / workflow 等服务在执行失败时调用。
 */
public record NotificationRequest(
        @NotBlank @Size(max = 64) String recipient,
        @NotBlank @Size(max = 32) String source,
        Long sourceId,
        @Size(max = 16) String level,
        @NotBlank @Size(max = 256) String title,
        @Size(max = 1024) String message,
        @Size(max = 256) String linkPath) {
}
