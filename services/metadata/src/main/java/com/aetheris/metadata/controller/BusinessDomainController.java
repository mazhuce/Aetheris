package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.BusinessDomain;
import com.aetheris.metadata.service.BusinessDomainService;
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

@RestController
@RequestMapping("/api/metadata/business-domains")
public class BusinessDomainController {
    private final BusinessDomainService service;

    public BusinessDomainController(BusinessDomainService service) { this.service = service; }

    @GetMapping
    public List<BusinessDomain> list(@RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status) {
        return service.list(keyword, status);
    }

    @GetMapping("/{id}")
    public BusinessDomain get(@PathVariable Long id) { return service.require(id); }

    @PostMapping
    public BusinessDomain create(@Valid @RequestBody BusinessDomain domain,
                                 @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
                                 @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.create(domain, username);
    }

    @PutMapping("/{id}")
    public BusinessDomain update(@PathVariable Long id, @Valid @RequestBody BusinessDomain domain,
                                 @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.update(id, domain);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) throw new ForbiddenException();
    }

    @ExceptionHandler(BusinessDomainService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理业务域"));
    }

    private static class ForbiddenException extends RuntimeException { }
}
