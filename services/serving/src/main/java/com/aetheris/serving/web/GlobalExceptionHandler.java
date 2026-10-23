package com.aetheris.serving.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public org.springframework.http.ResponseEntity<Map<String,Object>> databaseUnavailable(HttpServletRequest request){return org.springframework.http.ResponseEntity.status(503).body(body("DEPENDENCY_UNAVAILABLE","服务暂不可用",request));}
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String,Object>> status(org.springframework.web.server.ResponseStatusException ex,HttpServletRequest request){
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(body("REQUEST_REJECTED",ex.getReason()==null?"请求被拒绝":ex.getReason(),request));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return body("VALIDATION_FAILED", ex.getMessage() == null ? "请求参数无效" : ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> internalError(Exception ex, HttpServletRequest request) {
        return body("INTERNAL", "服务处理失败", request);
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) requestId = UUID.randomUUID().toString();
        return Map.of("requestId", requestId, "code", code, "message", message, "timestamp", Instant.now());
    }
}
