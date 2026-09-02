package com.aetheris.serving.controller;

import com.aetheris.serving.dto.SubscriptionInput;
import com.aetheris.serving.model.DataSubscription;
import com.aetheris.serving.service.ServingSubscriptionService;
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
@RequestMapping("/api/serving/subscriptions")
public class SubscriptionController {

    private final ServingSubscriptionService service;

    public SubscriptionController(ServingSubscriptionService service) {
        this.service = service;
    }

    @GetMapping
    public List<DataSubscription> list(@RequestParam(required = false) Optional<String> status) {
        return service.subscriptions(status);
    }

    @GetMapping("/{id}")
    public DataSubscription subscription(@PathVariable Long id) { return service.subscription(id); }

    @PostMapping
    public DataSubscription create(
            @Valid @RequestBody SubscriptionInput input,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.create(input, username);
    }

    @PutMapping("/{id}")
    public DataSubscription update(
            @PathVariable Long id,
            @Valid @RequestBody SubscriptionInput input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.update(id, input);
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

    @ExceptionHandler(ServingSubscriptionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ServingSubscriptionService.NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理数据订阅"));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
