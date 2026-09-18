package com.aetheris.quality.web;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
@RestControllerAdvice public class GlobalExceptionHandler {
 @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,Object>> bad(IllegalArgumentException e,HttpServletRequest request){return ResponseEntity.badRequest().body(body("VALIDATION_FAILED",safe(e),request));}
 @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,Object>> conflict(IllegalStateException e,HttpServletRequest request){return ResponseEntity.status(409).body(body("CONFLICT",safe(e),request));}
 @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,Object>> error(Exception e,HttpServletRequest request){return ResponseEntity.internalServerError().body(body("INTERNAL","服务处理失败",request));}
 private Map<String,Object> body(String code,String message,HttpServletRequest request){String requestId=request.getHeader("X-Request-Id");if(requestId==null||requestId.isBlank()||requestId.length()>128)requestId=UUID.randomUUID().toString();return Map.of("requestId",requestId,"code",code,"message",message,"timestamp",Instant.now());}
 private String safe(Exception e){return e.getMessage()==null||e.getMessage().isBlank()?"请求无法处理":e.getMessage();}
}
