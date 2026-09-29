package com.aetheris.serving.controller;

import com.aetheris.serving.dto.ApiKeyApplicationRequest;
import com.aetheris.serving.model.ApiKeyApplication;
import com.aetheris.serving.model.IssuedApiKey;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiKeyApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/serving/api-key-applications")
public class ApiKeyApplicationController {
    private final ApiKeyApplicationService service;
    private final ServingSecurity security;

    public ApiKeyApplicationController(ApiKeyApplicationService service, ServingSecurity security) {
        this.service = service;
        this.security = security;
    }

    @PostMapping
    public ApiKeyApplication request(@Valid @RequestBody ApiKeyApplicationRequest input, HttpServletRequest request) {
        return service.request(input, security.requireIdentity(request).username());
    }

    @GetMapping("/mine")
    public List<ApiKeyApplication> mine(HttpServletRequest request) {
        return service.list(Optional.of(security.requireIdentity(request).username()));
    }

    @GetMapping
    public List<ApiKeyApplication> list(HttpServletRequest request) {
        security.requireManage(request);
        return service.list(Optional.empty());
    }

    @PostMapping("/{id}/approve")
    public IssuedApiKey approve(@PathVariable long id, HttpServletRequest request) {
        security.requireManage(request);
        return service.approve(id, security.requireIdentity(request).username());
    }

    @PostMapping("/{id}/reject")
    public ApiKeyApplication reject(@PathVariable long id, @RequestParam(required = false) String comment, HttpServletRequest request) {
        security.requireManage(request);
        return service.reject(id, security.requireIdentity(request).username(), comment);
    }

    @ExceptionHandler(ApiKeyApplicationService.NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ApiKeyApplicationService.NotFoundException error) { return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ApiKeyApplicationService.ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ApiKeyApplicationService.ConflictException error) { return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.ForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage())); }
}
