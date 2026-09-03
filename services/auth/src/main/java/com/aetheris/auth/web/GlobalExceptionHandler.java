package com.aetheris.auth.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("PERMISSION_DENIED", error.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", error.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONFLICT", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(field -> field.getField() + " " + field.getDefaultMessage())
                .orElse("请求参数非法");
        return ResponseEntity.badRequest().body(ApiError.of("VALIDATION_FAILED", message));
    }

    @ExceptionHandler({InvalidRequestException.class, IllegalArgumentException.class,
            IllegalStateException.class})
    public ResponseEntity<ApiError> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_FAILED", error.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> internal(Exception error) {
        log.error("Unhandled error", error);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL", "服务内部错误"));
    }
}