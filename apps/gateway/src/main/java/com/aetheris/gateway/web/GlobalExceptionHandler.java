package com.aetheris.gateway.web;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message","请求参数无效"));}
 @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> error(Exception e){return ResponseEntity.internalServerError().body(Map.of("message","网关处理失败"));}
}
