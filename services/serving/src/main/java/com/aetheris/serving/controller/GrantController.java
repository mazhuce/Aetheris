package com.aetheris.serving.controller;

import com.aetheris.serving.dto.GrantInput;
import com.aetheris.serving.model.ServiceGrant;
import com.aetheris.serving.service.ServingGrantService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/serving/grants")
public class GrantController {

    private final ServingGrantService service;

    public GrantController(ServingGrantService service) {
        this.service = service;
    }

    @GetMapping
    public List<ServiceGrant> list(@RequestParam(required = false) Optional<String> status) {
        return service.grants(status);
    }

    @GetMapping("/{id}")
    public ServiceGrant grant(@PathVariable Long id) { return service.grant(id); }

    @PostMapping
    public ServiceGrant create(
            @Valid @RequestBody GrantInput input,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.create(input, username);
    }

    @PutMapping("/{id}")
    public ServiceGrant update(
            @PathVariable Long id,
            @Valid @RequestBody GrantInput input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.update(id, input);
    }

    @PostMapping("/{id}/revoke")
    public ServiceGrant revoke(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.revoke(id);
    }

    @PostMapping("/{id}/restore")
    public ServiceGrant restore(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.restore(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) throw new ForbiddenException();
    }

    public static class ForbiddenException extends RuntimeException { }

    @ExceptionHandler(ServingGrantService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ServingGrantService.NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理服务授权"));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
