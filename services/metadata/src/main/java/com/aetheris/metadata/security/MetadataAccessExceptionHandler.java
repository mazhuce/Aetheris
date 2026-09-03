package com.aetheris.metadata.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 权限相关异常的全局 JSON 响应：401 未认证 / 403 无权限。
 * 控制器内的局部 @ExceptionHandler 只登记各自业务异常，不会遮蔽这里的处理。
 */
@RestControllerAdvice
public class MetadataAccessExceptionHandler {

    @ExceptionHandler(MetadataSecurity.UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(MetadataSecurity.UnauthorizedException error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(MetadataSecurity.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden(MetadataSecurity.ForbiddenException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage()));
    }
}