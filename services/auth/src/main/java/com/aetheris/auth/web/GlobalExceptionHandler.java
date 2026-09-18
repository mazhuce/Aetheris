package com.aetheris.auth.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletRequest;

/**
 * auth-service 统一错误码出口。
 *
 * <p>错误码约定：
 * PERMISSION_DENIED（403）/ NOT_FOUND（404）/ VALIDATION_FAILED（400）/
 * CONFLICT（409）/ INTERNAL（500）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("PERMISSION_DENIED", error.getMessage(), request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", error.getMessage(), request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException error, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONFLICT", error.getMessage(), request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException error, HttpServletRequest request) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(field -> field.getField() + " " + field.getDefaultMessage())
                .orElse("请求参数非法");
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", message,
                request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class,
            IllegalStateException.class})
    public ResponseEntity<ApiError> badRequest(RuntimeException error, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", error.getMessage(), request.getHeader("X-Request-Id")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception error, HttpServletRequest request) {
        log.error("Unhandled error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL", "服务内部错误", request.getHeader("X-Request-Id")));
    }
}
