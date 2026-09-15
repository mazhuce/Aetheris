package com.aetheris.system.web;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> bad(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("message",safe(e)));}
 @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> error(Exception e){return ResponseEntity.internalServerError().body(Map.of("message","服务处理失败"));}
 private String safe(Exception e){return e.getMessage()==null||e.getMessage().isBlank()?"请求参数无效":e.getMessage();}
}
