package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.LogicalModel;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.LogicalModelService;
import com.aetheris.metadata.service.SemanticAssetReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/logical-models")
public class LogicalModelController {
    private final LogicalModelService service;
    private final MetadataSecurity security;
    private final SemanticAssetReviewService reviewService;

    public LogicalModelController(LogicalModelService service, MetadataSecurity security, SemanticAssetReviewService reviewService) {
        this.service = service;
        this.security = security;
        this.reviewService = reviewService;
    }

    @GetMapping public List<LogicalModel> list(HttpServletRequest request,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        MetadataSecurity.Scope scope = security.visibility(request, "model", "view");
        return switch (scope.mode()) {
            case ALL -> service.list(keyword, status);
            case IDS -> service.list(keyword, status, scope.ids());
            case OPAQUE -> filteredList(request, service.list(keyword, status));
        };
    }

    @GetMapping("/{id}") public LogicalModel get(HttpServletRequest request, @PathVariable Long id) {
        LogicalModel model = service.require(id);
        security.require(request, "model", id, "view", domainCtx(model.getBusinessDomainId()));
        return model;
    }

    @GetMapping("/{id}/versions") public List<SemanticVersion> versions(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "view");
        return service.versions(id);
    }

    @PostMapping public LogicalModel create(HttpServletRequest request, @Valid @RequestBody LogicalModel model) {
        String username = security.require(request, "model", null, "create",
                domainCtx(model.getBusinessDomainId())).username();
        return service.create(model, username);
    }

    @PutMapping("/{id}") public LogicalModel update(HttpServletRequest request, @PathVariable Long id,
                                                    @Valid @RequestBody LogicalModel model) {
        requireAccess(request, id, "update");
        return service.update(id, model);
    }

    @PostMapping("/{id}/validate") public Map<String, Object> validate(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "query");
        return service.validate(id);
    }

    @PostMapping("/{id}/publish") public LogicalModel publish(HttpServletRequest request, @PathVariable Long id) {
        String username = requireAccess(request, id, "publish");
        var model = service.require(id);
        var review = reviewService.requireApproved("logical_model", id, model.getVersion());
        var published = service.publish(id, username);
        reviewService.markPublished(review.getId());
        return published;
    }

    @PostMapping("/{id}/new-version") public LogicalModel newVersion(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "update");
        return service.newVersion(id);
    }

    @PostMapping("/{id}/discard-draft") public LogicalModel discardDraft(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "update");
        return service.discardDraft(id);
    }

    @PostMapping("/{id}/deprecate") public LogicalModel deprecate(HttpServletRequest request, @PathVariable Long id) {
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
        LogicalModel model = service.require(id);
        return security.require(request, "model", id, action,
                domainCtx(model.getBusinessDomainId())).username();
    }

    private List<LogicalModel> filteredList(HttpServletRequest request, List<LogicalModel> models) {
        String username = security.requireIdentity(request).username();
        return models.stream()
                .filter(model -> security.isAllowed(username, "model", model.getId(), "view",
                        domainCtx(model.getBusinessDomainId())))
                .toList();
    }

    private static Map<String, String> domainCtx(Long domainId) {
        return domainId == null ? Map.of() : Map.of("domainId", String.valueOf(domainId));
    }

    @ExceptionHandler(LogicalModelService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
