package com.aetheris.serving.controller;

import com.aetheris.serving.dto.SubscriptionInput;
import com.aetheris.serving.model.DataSubscription;
import com.aetheris.serving.service.ServingSubscriptionService;
import com.aetheris.serving.security.ServingSecurity;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/serving/subscriptions")
public class SubscriptionController {

    private final ServingSubscriptionService service;
    private final ServingSecurity security;

    @Autowired
    public SubscriptionController(ServingSubscriptionService service, ServingSecurity security) {
        this.service = service; this.security = security;
    }
    public SubscriptionController(ServingSubscriptionService service) { this(service, null); }
    public List<DataSubscription> list(Optional<String> status) { return service.subscriptions(status); }
    public DataSubscription subscription(Long id) { return service.subscription(id); }
    public DataSubscription create(SubscriptionInput input, String username, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.create(input, username); }
    public DataSubscription update(Long id, SubscriptionInput input, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.update(id, input); }
    public ResponseEntity<Void> delete(Long id, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); service.delete(id); return ResponseEntity.noContent().build(); }

    @GetMapping
    public List<DataSubscription> list(HttpServletRequest request, @RequestParam(required = false) Optional<String> status) {
        security.require(request,"dataset",null,"view");
        return service.subscriptions(status);
    }

    @GetMapping("/{id}")
    public DataSubscription subscription(HttpServletRequest request,@PathVariable Long id) { security.require(request,"dataset",id,"view"); return service.subscription(id); }

    @PostMapping
    public DataSubscription create(
            @Valid @RequestBody SubscriptionInput input,
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String ignoredUsername,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        security.requireManage(request);
        return service.create(input, security.requireIdentity(request).username());
    }

    @PutMapping("/{id}")
    public DataSubscription update(
            @PathVariable Long id,
            @Valid @RequestBody SubscriptionInput input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            jakarta.servlet.http.HttpServletRequest request) {
        security.requireManage(request);
        return service.update(id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            jakarta.servlet.http.HttpServletRequest request) {
        security.requireManage(request);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public static class ForbiddenException extends RuntimeException { }

    @ExceptionHandler(ServingSubscriptionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ServingSubscriptionService.NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理数据订阅"));
    }

    @ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler(ServingSecurity.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> secureForbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage())); }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
