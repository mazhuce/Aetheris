package com.aetheris.workflow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", safe(error)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", safe(error)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception error) {
        return ResponseEntity.internalServerError().body(Map.of("message", "服务暂时不可用，请稍后重试"));
    }

    private String safe(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "请求无法处理" : error.getMessage();
    }
}
