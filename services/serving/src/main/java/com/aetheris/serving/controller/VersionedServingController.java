package com.aetheris.serving.controller;

import com.aetheris.serving.dto.VersionedSqlQueryRequest;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.security.ServingRateLimiter;
import com.aetheris.serving.service.MetadataQueryClient;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@RestController
@RequestMapping("/api/serving/v1")
@Validated
public class VersionedServingController {
    private final MetadataQueryClient metadata; private final ServingSecurity security; private final ServingRateLimiter rateLimiter;
    public VersionedServingController(MetadataQueryClient metadata, ServingSecurity security, ServingRateLimiter rateLimiter){this.metadata=metadata;this.security=security;this.rateLimiter=rateLimiter;}
    @PostMapping("/metrics/{code}/query")
    public Map<String,Object> metric(HttpServletRequest request,@PathVariable String code,@RequestParam(required=false) String dimensions,@RequestParam(required=false) String timeGrain,@RequestParam(required=false) String startDate,@RequestParam(required=false) String endDate,@RequestParam(defaultValue="100") @Min(1) @Max(100) Integer limit){
        if(!code.matches("[A-Za-z][A-Za-z0-9_]{1,63}"))throw new IllegalArgumentException("指标编码格式无效");
        var identity=security.requireIdentity(request);
        security.require(request,"metric",null,"query");
        if(!rateLimiter.allow(identity.username(),"metric:"+code))throw new RateLimitException();
        Map<String,Object> body=new LinkedHashMap<>();body.put("dimensions",parseDimensions(dimensions));body.put("timeGrain",timeGrain);body.put("startDate",startDate);body.put("endDate",endDate);body.put("limit",limit);return metadata.queryMetric(code,body);
    }
    @PostMapping("/query")
    public Map<String,Object> sql(HttpServletRequest request,@Valid @RequestBody VersionedSqlQueryRequest body){var identity=security.requireIdentity(request);security.require(request,"datasource",body.datasourceId(),"query");if(!rateLimiter.allow(identity.username(),"sql:"+body.datasourceId()))throw new RateLimitException();return metadata.query(body.datasourceId(),body.sql().trim());}
    @PostMapping("/tables/{id}/query")
    public Map<String,Object> table(HttpServletRequest request,@PathVariable Long id,@RequestParam(required=false) String columns,@RequestParam(defaultValue="100") @Min(1) @Max(100) Integer limit){
        var identity=security.requireIdentity(request);security.require(request,"table",id,"query");if(!rateLimiter.allow(identity.username(),"table:"+id))throw new RateLimitException();
        Map<String,Object> body=new LinkedHashMap<>();body.put("columns",parseColumns(columns));body.put("limit",limit);return metadata.queryTable(id,body);
    }
    private List<String> parseDimensions(String value){
        if(value==null||value.isBlank())return List.of();
        if(value.length()>512)throw new IllegalArgumentException("维度参数过长");
        List<String> result=Arrays.stream(value.split(",",-1)).map(String::trim).filter(s->!s.isBlank()).toList();
        if(result.size()>8)throw new IllegalArgumentException("单次查询最多使用 8 个维度");
        if(result.stream().anyMatch(s->!s.matches("[A-Za-z][A-Za-z0-9_]{1,63}")))throw new IllegalArgumentException("维度编码格式无效");
        return result;
    }
    private List<String> parseColumns(String value){
        if(value==null||value.isBlank())return List.of();
        if(value.length()>2000)throw new IllegalArgumentException("字段参数过长");
        List<String> result=Arrays.stream(value.split(",",-1)).map(String::trim).filter(s->!s.isBlank()).toList();
        if(result.size()>50)throw new IllegalArgumentException("单次查询最多选择 50 个字段");
        if(result.stream().anyMatch(s->!s.matches("[A-Za-z_][A-Za-z0-9_$]*")))throw new IllegalArgumentException("字段名格式无效");
        return result;
    }
    @ExceptionHandler(ServingSecurity.UnauthorizedException.class) public ResponseEntity<Map<String,String>> unauthorized(ServingSecurity.UnauthorizedException e){return ResponseEntity.status(401).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(ServingSecurity.ForbiddenException.class) public ResponseEntity<Map<String,String>> forbidden(ServingSecurity.ForbiddenException e){return ResponseEntity.status(403).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(RateLimitException.class) public ResponseEntity<Map<String,String>> rateLimited(){return ResponseEntity.status(429).header("Retry-After","60").body(Map.of("message","接口调用频率超限，请稍后重试"));}
    @ExceptionHandler(ConstraintViolationException.class) public ResponseEntity<Map<String,String>> constraintViolation(){return ResponseEntity.badRequest().body(Map.of("message","请求参数不符合限制"));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<Map<String,String>> bad(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    private static final class RateLimitException extends RuntimeException { }
}
