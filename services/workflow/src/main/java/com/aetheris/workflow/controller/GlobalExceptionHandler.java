package com.aetheris.workflow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> status(org.springframework.web.server.ResponseStatusException error,HttpServletRequest request){
        int status=error.getStatusCode().value();String code=switch(status){case 401->"UNAUTHORIZED";case 403->"FORBIDDEN";case 503->"UNAVAILABLE";default->"REQUEST_REJECTED";};
        return ResponseEntity.status(status).body(body(code,status==503?"权限服务暂时不可用":"身份或权限检查未通过",request));
    }
    @ExceptionHandler({org.springframework.web.bind.MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String,Object>> invalidBody(Exception error,HttpServletRequest request){
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED","请求参数格式或必填字段无效",request));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException error, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", safe(error), request));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("CONFLICT", safe(error), request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception error, HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(body("INTERNAL", "服务暂时不可用，请稍后重试", request));
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) requestId = UUID.randomUUID().toString();
        return Map.of("requestId", requestId, "code", code, "message", message, "timestamp", Instant.now());
    }

    private String safe(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "请求无法处理" : error.getMessage();
    }
}
