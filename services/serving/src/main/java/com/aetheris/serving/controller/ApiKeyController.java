package com.aetheris.serving.controller;

import com.aetheris.serving.dto.ApiKeyCreateRequest;
import com.aetheris.serving.model.ApiKeySummary;
import com.aetheris.serving.model.IssuedApiKey;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/serving/api-keys")
public class ApiKeyController {
    private final ApiKeyService service;
    private final ServingSecurity security;

    public ApiKeyController(ApiKeyService service, ServingSecurity security) { this.service = service; this.security = security; }

    @GetMapping
    public List<ApiKeySummary> list(HttpServletRequest request) { security.requireManage(request); return service.list(); }

    @PostMapping
    public IssuedApiKey create(@Valid @RequestBody ApiKeyCreateRequest input, HttpServletRequest request) {
        security.requireManage(request);
        String actor = security.requireIdentity(request).username();
        return service.create(input, actor, actor);
    }

    @PostMapping("/{id}/rotate")
    public IssuedApiKey rotate(@PathVariable long id, HttpServletRequest request) {
        security.requireManage(request);
        return service.rotate(id, security.requireIdentity(request).username());
    }

    @PostMapping("/{id}/revoke")
    public ApiKeySummary revoke(@PathVariable long id, HttpServletRequest request) {
        security.requireManage(request);
        return service.revoke(id);
    }

    @ExceptionHandler(ApiKeyService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ApiKeyService.NotFoundException error) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ApiKeyService.ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ApiKeyService.ConflictException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage())); }
}
