package com.aetheris.serving.controller;

import com.aetheris.serving.dto.ApiSlaPolicyRequest;
import com.aetheris.serving.model.ApiSlaEvaluation;
import com.aetheris.serving.model.ApiSlaPolicy;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiSlaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/serving/sla")
public class ApiSlaController {
    private final ApiSlaService service;
    private final ServingSecurity security;

    public ApiSlaController(ApiSlaService service, ServingSecurity security) { this.service = service; this.security = security; }

    @GetMapping
    public List<ApiSlaPolicy> list(HttpServletRequest request) { security.requireManage(request); return service.list(); }

    @PutMapping("/{assetCode}")
    public ApiSlaPolicy upsert(@PathVariable String assetCode, @Valid @RequestBody ApiSlaPolicyRequest input, HttpServletRequest request) {
        security.requireManage(request);
        return service.upsert(assetCode, input, security.requireIdentity(request).username());
    }

    @GetMapping("/{assetCode}/evaluation")
    public ApiSlaEvaluation evaluation(
            @PathVariable String assetCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            HttpServletRequest request) {
        security.requireManage(request);
        return service.evaluate(assetCode, from, to);
    }

    @ExceptionHandler(ApiSlaService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ApiSlaService.NotFoundException error) { return ResponseEntity.status(404).body(Map.of("message", error.getMessage())); }
    @ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(401).body(Map.of("message", error.getMessage())); }
    @ExceptionHandler(ServingSecurity.ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(403).body(Map.of("message", error.getMessage())); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("message", error.getMessage())); }
}
