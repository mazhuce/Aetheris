package com.aetheris.gateway.audit;

public record AuditEvent(
        String username,
        String userRole,
        String method,
        String path,
        Integer statusCode,
        Long durationMs,
        String clientIp) {
}
