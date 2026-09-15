package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.MetricDefinitionService;
import com.aetheris.metadata.service.SemanticAssetReviewService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final MetadataSecurity security;
    private final SemanticAssetReviewService reviewService;

    public MetricDefinitionController(MetricDefinitionService service, MetadataSecurity security, SemanticAssetReviewService reviewService) {
        this.service = service;
        this.security = security;
        this.reviewService = reviewService;
    }

    @GetMapping public List<MetricDefinition> list(HttpServletRequest request,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status) {
        MetadataSecurity.Scope scope = security.visibility(request, "metric", "view");
        return switch (scope.mode()) {
            case ALL -> service.list(keyword, status);
            case IDS -> service.list(keyword, status, scope.ids());
            case OPAQUE -> filteredList(request, service.list(keyword, status));
        };
    }

    @GetMapping("/{id}") public MetricDefinition get(HttpServletRequest request, @PathVariable Long id) {
        MetricDefinition metric = service.require(id);
        security.require(request, "metric", id, "view", domainCtx(metric.getBusinessDomainId()));
        return metric;
    }

    @GetMapping("/{id}/versions") public List<SemanticVersion> versions(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "view");
        return service.versions(id);
    }

    @PostMapping public MetricDefinition create(HttpServletRequest request, @Valid @RequestBody MetricDefinition metric) {
        String username = security.require(request, "metric", null, "create",
                domainCtx(metric.getBusinessDomainId())).username();
        return service.create(metric, username);
    }

    @PutMapping("/{id}") public MetricDefinition update(HttpServletRequest request, @PathVariable Long id,
                                                        @Valid @RequestBody MetricDefinition metric) {
        requireAccess(request, id, "update");
        return service.update(id, metric);
    }

    @PostMapping("/{id}/validate") public Map<String, Object> validate(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "query");
        return service.validate(id);
    }

    @PostMapping("/{id}/preview") public Map<String, Object> preview(HttpServletRequest request, @PathVariable Long id,
            @Valid @RequestBody(required = false) MetricQueryRequest queryRequest) {
        requireAccess(request, id, "query");
        return service.preview(id, queryRequest);
    }

    @PostMapping("/{id}/publish") public MetricDefinition publish(HttpServletRequest request, @PathVariable Long id) {
        String username = requireAccess(request, id, "publish");
        var metric = service.require(id);
        var review = reviewService.requireApproved("metric_definition", id, metric.getVersion());
        var published = service.publish(id, username);
        reviewService.markPublished(review.getId());
        return published;
    }

    @PostMapping("/{id}/new-version") public MetricDefinition newVersion(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "update");
        return service.newVersion(id);
    }

    @PostMapping("/{id}/discard-draft") public MetricDefinition discardDraft(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "update");
        return service.discardDraft(id);
    }

    @PostMapping("/{id}/deprecate") public MetricDefinition deprecate(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "deprecate");
        return service.deprecate(id);
    }

    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "delete");
        service.remove(id);
        return ResponseEntity.noContent().build();
    }

    /** 先取资源归属域（顺带 404），再做资源级判定。 */
    private String requireAccess(HttpServletRequest request, Long id, String action) {
        MetricDefinition metric = service.require(id);
        return security.require(request, "metric", id, action,
                domainCtx(metric.getBusinessDomainId())).username();
    }

    private List<MetricDefinition> filteredList(HttpServletRequest request, List<MetricDefinition> metrics) {
        String username = security.requireIdentity(request).username();
        java.util.Set<Long> allowed = security.allowedIds(username, "metric", "view",
                metrics.stream().collect(java.util.stream.Collectors.toMap(MetricDefinition::getId,
                        metric -> domainCtx(metric.getBusinessDomainId()), (left, right) -> left)));
        return metrics.stream()
                .filter(metric -> allowed.contains(metric.getId()))
                .toList();
    }

    private static Map<String, String> domainCtx(Long domainId) {
        return domainId == null ? Map.of() : Map.of("domainId", String.valueOf(domainId));
    }

    @ExceptionHandler(MetricDefinitionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
