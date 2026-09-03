package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.service.MetricDefinitionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/metric-definitions")
public class MetricDefinitionController {
    private final MetricDefinitionService service;
    public MetricDefinitionController(MetricDefinitionService service) { this.service = service; }

    @GetMapping public List<MetricDefinition> list(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status) {
        return service.list(keyword, status);
    }
    @GetMapping("/{id}") public MetricDefinition get(@PathVariable Long id) { return service.require(id); }
    @GetMapping("/{id}/versions") public List<SemanticVersion> versions(@PathVariable Long id) { return service.versions(id); }
    @PostMapping public MetricDefinition create(@Valid @RequestBody MetricDefinition metric,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.create(metric, username);
    }
    @PutMapping("/{id}") public MetricDefinition update(@PathVariable Long id, @Valid @RequestBody MetricDefinition metric,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.update(id, metric);
    }
    @PostMapping("/{id}/validate") public Map<String, Object> validate(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.validate(id);
    }
    @PostMapping("/{id}/preview") public Map<String, Object> preview(@PathVariable Long id,
            @Valid @RequestBody(required = false) MetricQueryRequest request,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.preview(id, request);
    }
    @PostMapping("/{id}/publish") public MetricDefinition publish(@PathVariable Long id,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.publish(id, username);
    }
    @PostMapping("/{id}/new-version") public MetricDefinition newVersion(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.newVersion(id);
    }
    @PostMapping("/{id}/discard-draft") public MetricDefinition discardDraft(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.discardDraft(id);
    }
    @PostMapping("/{id}/deprecate") public MetricDefinition deprecate(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); return service.deprecate(id);
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role); service.remove(id); return ResponseEntity.noContent().build();
    }

    private void requireAdmin(String role) { if (!"admin".equals(role)) throw new ForbiddenException(); }
    @ExceptionHandler(MetricDefinitionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以管理指标"));
    }
    private static class ForbiddenException extends RuntimeException { }
}
