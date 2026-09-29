package com.aetheris.metadata.security;

import com.aetheris.metadata.service.ApiAssetService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.servlet.http.HttpServletRequest;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 权限相关异常的全局 JSON 响应：401 未认证 / 403 无权限。
 * 控制器内的局部 @ExceptionHandler 只登记各自业务异常，不会遮蔽这里的处理。
 */
@RestControllerAdvice
public class MetadataAccessExceptionHandler {

    @ExceptionHandler(MetadataSecurity.UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized(MetadataSecurity.UnauthorizedException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body("UNAUTHORIZED", error.getMessage(), request));
    }

    @ExceptionHandler(MetadataSecurity.ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden(MetadataSecurity.ForbiddenException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body("PERMISSION_DENIED", error.getMessage(), request));
    }

    @ExceptionHandler(MetadataSecurity.PermissionServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> permissionUnavailable(
            MetadataSecurity.PermissionServiceUnavailableException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body("AUTH_UNAVAILABLE", error.getMessage(), request));
    }

    @ExceptionHandler(ApiAssetService.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> apiAssetNotFound(ApiAssetService.NotFoundException error,
                                                                  HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("NOT_FOUND", error.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst().map(field -> field.getField() + " " + field.getDefaultMessage())
                .orElse("请求参数非法");
        return ResponseEntity.badRequest().body(body("VALIDATION_FAILED", message, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> internal(Exception error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL", "服务内部错误", request));
    }

    private Map<String, Object> body(String code, String message, HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) requestId = UUID.randomUUID().toString();
        return Map.of("requestId", requestId, "code", code,
                "message", message == null || message.isBlank() ? "请求无法处理" : message,
                "timestamp", Instant.now());
    }
}
