package com.aetheris.learning.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException error, HttpServletRequest request) {
        return ResponseEntity.status(error.getStatusCode()).body(body(
                error.getStatusCode().value() == 403 ? "FORBIDDEN" : "REQUEST_REJECTED",
                error.getReason() == null ? "请求被拒绝" : error.getReason(), request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> validation(Exception error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", "请求参数无效", request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> bad(IllegalArgumentException error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", safe(error), request));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException error, HttpServletRequest request) {
        return ResponseEntity.status(409).body(body("CONFLICT", safe(error), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> error(Exception error, HttpServletRequest request) {
        log.error("学习服务处理失败", error);
        return ResponseEntity.internalServerError().body(body("INTERNAL", "服务处理失败", request));
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            requestId = UUID.randomUUID().toString();
        }
        return Map.of("requestId", requestId, "code", code, "message", message, "timestamp", Instant.now());
    }

    private String safe(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "请求无法处理" : error.getMessage();
    }
}
