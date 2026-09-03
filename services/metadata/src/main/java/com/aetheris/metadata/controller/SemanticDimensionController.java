package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.SemanticDimension;
import com.aetheris.metadata.service.SemanticDimensionService;
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
@RequestMapping("/api/metadata/semantic-dimensions")
public class SemanticDimensionController {
    private final SemanticDimensionService service;
    public SemanticDimensionController(SemanticDimensionService service) { this.service = service; }

    @GetMapping
    public List<SemanticDimension> list(@RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long tableId) {
        return service.list(keyword, status, tableId);
    }
    @GetMapping("/{id}") public SemanticDimension get(@PathVariable Long id) { return service.require(id); }
    @GetMapping("/{id}/versions") public List<SemanticVersion> versions(@PathVariable Long id) { return service.versions(id); }
    @PostMapping public SemanticDimension create(@Valid @RequestBody SemanticDimension input,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.create(input, username);
    }
    @PutMapping("/{id}") public SemanticDimension update(@PathVariable Long id, @Valid @RequestBody SemanticDimension input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.update(id, input);
    }
    @PostMapping("/{id}/validate") public Map<String, Object> validate(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.validate(id);
    }
    @PostMapping("/{id}/publish") public SemanticDimension publish(@PathVariable Long id,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.publish(id, username);
    }
    @PostMapping("/{id}/new-version") public SemanticDimension newVersion(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.newVersion(id);
    }
    @PostMapping("/{id}/discard-draft") public SemanticDimension discardDraft(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.discardDraft(id);
    }
    @PostMapping("/{id}/deprecate") public SemanticDimension deprecate(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.deprecate(id);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); service.delete(id); return ResponseEntity.noContent().build();
    }

    private void requireAdmin(String role) { if (!"admin".equals(role)) throw new ForbiddenException(); }
    @ExceptionHandler(SemanticDimensionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理语义维度"));
    }
    private static class ForbiddenException extends RuntimeException { }
}
