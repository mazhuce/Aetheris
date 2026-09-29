package com.aetheris.serving.controller;

import com.aetheris.serving.model.ApiUsageSummary;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiUsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/serving/usage")
public class ApiUsageController {
    private final ApiUsageService service;
    private final ServingSecurity security;

    public ApiUsageController(ApiUsageService service, ServingSecurity security) { this.service = service; this.security = security; }

    @GetMapping
    public List<ApiUsageSummary> summary(
            HttpServletRequest request,
            @RequestParam(required = false) String assetCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "50") int limit) {
        security.requireManage(request);
        if (assetCode != null && !assetCode.isBlank() && !assetCode.matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
            throw new IllegalArgumentException("API 资产编码格式无效");
        }
        if (from != null && to != null && !from.isBefore(to)) throw new IllegalArgumentException("统计时间范围无效");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("统计结果数量必须在 1 到 100 之间");
        return service.summary(assetCode, from, to, limit);
    }

    @ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(401).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(403).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage())); }
}
