package com.aetheris.serving.controller;

import com.aetheris.serving.dto.VersionedSqlQueryRequest;
import com.aetheris.serving.dto.ApiAssetQueryRequest;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.security.ServingRateLimiter;
import com.aetheris.serving.service.MetadataQueryClient;
import com.aetheris.serving.service.ApiKeyService;
import com.aetheris.serving.service.ApiUsageService;
import com.aetheris.serving.service.DistributedApiRateLimiter;
import com.aetheris.serving.security.ServingIdentity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;
import java.util.*;

@RestController
@RequestMapping("/api/serving/v1")
@Validated
public class VersionedServingController {
    private final MetadataQueryClient metadata; private final ServingSecurity security; private final ServingRateLimiter rateLimiter; private final ApiKeyService apiKeys; private final ApiUsageService usage; private final DistributedApiRateLimiter distributedRateLimiter;
    public VersionedServingController(MetadataQueryClient metadata, ServingSecurity security, ServingRateLimiter rateLimiter){this(metadata,security,rateLimiter,null,null,null);}
    public VersionedServingController(MetadataQueryClient metadata, ServingSecurity security, ServingRateLimiter rateLimiter, ApiKeyService apiKeys){this(metadata,security,rateLimiter,apiKeys,null,null);}
    public VersionedServingController(MetadataQueryClient metadata, ServingSecurity security, ServingRateLimiter rateLimiter, ApiKeyService apiKeys, ApiUsageService usage){this(metadata,security,rateLimiter,apiKeys,usage,null);}
    @Autowired
    public VersionedServingController(MetadataQueryClient metadata, ServingSecurity security, ServingRateLimiter rateLimiter, ApiKeyService apiKeys, ApiUsageService usage, DistributedApiRateLimiter distributedRateLimiter){this.metadata=metadata;this.security=security;this.rateLimiter=rateLimiter;this.apiKeys=apiKeys;this.usage=usage;this.distributedRateLimiter=distributedRateLimiter;}
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

    /** Execute only a published API asset; callers never submit SQL or choose the target. */
    @PostMapping("/apis/{assetCode}/query")
    public Map<String, Object> apiAsset(HttpServletRequest request,
                                        @PathVariable String assetCode,
                                        @Valid @RequestBody(required = false) ApiAssetQueryRequest queryRequest) {
        if (!assetCode.matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("API 资产编码格式无效");
        }
        long started = System.nanoTime();
        int[] status = {200};
        String[] error = {null};
        ServingIdentity[] resolved = {null};
        String presentedApiKey = request.getHeader("X-API-Key");
        String authType = presentedApiKey == null || presentedApiKey.isBlank() ? "identity" : "api_key";
        try {
            var identity = requireApiAssetIdentity(request);
            resolved[0] = identity;
            Map<String, Object> contract = metadata.publishedApiAsset(assetCode);
            Long assetId = number(contract.get("id"), "API 资产契约缺少 ID");
            security.require(request, "data_service", assetId, "execute");
            if (!rateLimiter.allow(identity.username(), "api:" + assetCode)) throw new RateLimitException();
            if ("identity".equals(authType) && distributedRateLimiter != null && !distributedRateLimiter.allow(identity.username(), assetCode)) throw new RateLimitException();

            ApiAssetQueryRequest actual = queryRequest == null
                    ? new ApiAssetQueryRequest(List.of(), null, null, null, List.of(), 100)
                    : queryRequest;
            String targetType = String.valueOf(contract.get("targetType"));
            String targetRef = String.valueOf(contract.get("targetRef"));
            if ("metric".equals(targetType)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("dimensions", parseDimensionList(actual.dimensions()));
                body.put("timeGrain", actual.timeGrain());
                body.put("startDate", actual.startDate());
                body.put("endDate", actual.endDate());
                body.put("limit", actual.limit() == null ? 100 : actual.limit());
                return metadata.queryMetric(targetRef, body);
            }
            if ("table".equals(targetType)) {
                Long tableId = number(targetRef, "table 执行目标无效");
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("columns", parseColumnList(actual.columns()));
                body.put("limit", actual.limit() == null ? 100 : actual.limit());
                return metadata.queryTable(tableId, body);
            }
            throw new IllegalStateException("API 资产执行目标类型不受支持");
        } catch (RuntimeException exception) {
            status[0] = statusFor(exception);
            error[0] = exception.getClass().getSimpleName();
            throw exception;
        } finally {
            Object principal = request.getAttribute(ServingSecurity.ATTRIBUTE);
            String subject = resolved[0] != null ? resolved[0].username() : (principal instanceof ServingIdentity identity ? identity.username() : "unknown");
            if (usage != null) usage.record(assetCode, subject, authType, status[0], (System.nanoTime() - started) / 1_000_000, error[0]);
        }
    }
    private static int statusFor(RuntimeException exception) {
        if (exception instanceof ServingSecurity.UnauthorizedException) return 401;
        if (exception instanceof ServingSecurity.ForbiddenException) return 403;
        if (exception instanceof MetadataQueryClient.ApiAssetNotFoundException) return 404;
        if (exception instanceof RateLimitException) return 429;
        if (exception instanceof IllegalArgumentException) return 400;
        return 500;
    }
    private ServingIdentity requireApiAssetIdentity(HttpServletRequest request) {
        String presented = request.getHeader("X-API-Key");
        if (presented == null || presented.isBlank()) {
            ServingIdentity identity = security.requireIdentity(request);
            request.setAttribute(ServingSecurity.ATTRIBUTE, identity);
            return identity;
        }
        if (apiKeys == null) throw new ServingSecurity.UnauthorizedException("API key 验证未配置");
        ApiKeyService.ApiKeyPrincipal principal = apiKeys.authenticate(presented)
                .orElseThrow(() -> new ServingSecurity.UnauthorizedException("API key 无效或已过期"));
        if (!apiKeys.consumeQuota(principal.id())) throw new RateLimitException();
        ServingIdentity identity = new ServingIdentity(null, principal.ownerUsername());
        request.setAttribute(ServingSecurity.ATTRIBUTE, identity);
        return identity;
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
    private List<String> parseDimensionList(List<String> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(value -> value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{1,63}"))) {
            throw new IllegalArgumentException("维度编码格式无效");
        }
        return values.stream().map(String::trim).distinct().toList();
    }
    private List<String> parseColumnList(List<String> values) {
        if (values == null) return List.of();
        if (values.stream().anyMatch(value -> value == null || !value.matches("[A-Za-z_][A-Za-z0-9_$]*"))) {
            throw new IllegalArgumentException("字段名格式无效");
        }
        return values.stream().map(String::trim).distinct().toList();
    }
    private static Long number(Object value, String message) {
        try { return Long.parseLong(String.valueOf(value)); }
        catch (RuntimeException error) { throw new IllegalStateException(message); }
    }
    @ExceptionHandler(ServingSecurity.UnauthorizedException.class) public ResponseEntity<Map<String,String>> unauthorized(ServingSecurity.UnauthorizedException e){return ResponseEntity.status(401).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(ServingSecurity.ForbiddenException.class) public ResponseEntity<Map<String,String>> forbidden(ServingSecurity.ForbiddenException e){return ResponseEntity.status(403).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(MetadataQueryClient.ApiAssetNotFoundException.class) public ResponseEntity<Map<String,String>> apiAssetNotFound(MetadataQueryClient.ApiAssetNotFoundException e){return ResponseEntity.status(404).body(Map.of("message",e.getMessage()));}
    @ExceptionHandler(RateLimitException.class) public ResponseEntity<Map<String,String>> rateLimited(){return ResponseEntity.status(429).header("Retry-After","60").body(Map.of("message","接口调用频率超限，请稍后重试"));}
    @ExceptionHandler(ConstraintViolationException.class) public ResponseEntity<Map<String,String>> constraintViolation(){return ResponseEntity.badRequest().body(Map.of("message","请求参数不符合限制"));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<Map<String,String>> bad(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("message",e.getMessage()));}
    private static final class RateLimitException extends RuntimeException { }
}
