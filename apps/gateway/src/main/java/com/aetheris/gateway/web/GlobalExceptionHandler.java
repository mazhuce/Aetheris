package com.aetheris.gateway.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException error, ServerWebExchange exchange) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", "请求参数无效", exchange));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> error(Exception error, ServerWebExchange exchange) {
        return ResponseEntity.internalServerError().body(body("INTERNAL", "网关处理失败", exchange));
    }

    private Map<String, Object> body(String code, String message, ServerWebExchange exchange) {
        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) requestId = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        return Map.of("requestId", requestId, "code", code, "message", message, "timestamp", Instant.now());
    }
}
