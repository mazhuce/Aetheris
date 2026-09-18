package com.aetheris.auth.web;

import java.time.Instant;
import java.util.UUID;

/** 统一错误响应体，保留可追踪 requestId 与稳定错误码。 */
public record ApiError(String requestId, String code, String message, Instant timestamp) {

    public static ApiError of(String code, String message) {
        return of(code, message, null);
    }

    public static ApiError of(String code, String message, String requestId) {
        String normalized = requestId == null || requestId.isBlank() || requestId.length() > 128
                ? UUID.randomUUID().toString() : requestId;
        String safeMessage = message == null || message.isBlank() ? "请求无法处理" : message;
        return new ApiError(normalized, code, safeMessage, Instant.now());
    }
}
