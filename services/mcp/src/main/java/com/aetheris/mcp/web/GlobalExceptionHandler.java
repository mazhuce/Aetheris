package com.aetheris.mcp.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", error.getMessage(), request));
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(Exception error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("INVALID_JSON", "请求体不是合法的 JSON", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> error(Exception error, HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(body("INTERNAL", "服务处理失败", request));
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            requestId = UUID.randomUUID().toString();
        }
        return Map.of("requestId", requestId, "code", code,
                "message", message == null || message.isBlank() ? "请求无法处理" : message,
                "timestamp", Instant.now());
    }
}
