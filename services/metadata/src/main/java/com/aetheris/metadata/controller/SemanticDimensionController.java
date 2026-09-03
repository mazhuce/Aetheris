package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.SemanticDimension;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.SemanticDimensionService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata/semantic-dimensions")
public class SemanticDimensionController {
    private final SemanticDimensionService service;
    private final MetadataSecurity security;

    public SemanticDimensionController(SemanticDimensionService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping
    public List<SemanticDimension> list(HttpServletRequest request,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Long tableId) {
        if (tableId != null) {
            // 按表过滤时先判定该表的读取权限，避免借表 id 枚举跨域维度。
            security.require(request, "table", tableId, "view", Map.of());
        }
        MetadataSecurity.Scope scope = security.visibility(request, "dimension", "view");
        return switch (scope.mode()) {
            case ALL -> service.list(keyword, status, tableId);
            case IDS -> service.list(keyword, status, tableId, scope.ids());
            case OPAQUE -> filteredList(request, service.list(keyword, status, tableId));
        };
    }

    @GetMapping("/{id}") public SemanticDimension get(HttpServletRequest request, @PathVariable Long id) {
        SemanticDimension dimension = service.require(id);
        security.require(request, "dimension", id, "view", domainCtx(dimension.getBusinessDomainId()));
        return dimension;
    }

    @GetMapping("/{id}/versions") public List<SemanticVersion> versions(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "view");
        return service.versions(id);
    }

    @PostMapping public SemanticDimension create(HttpServletRequest request, @Valid @RequestBody SemanticDimension input) {
        String username = security.require(request, "dimension", null, "create",
                domainCtx(input.getBusinessDomainId())).username();
        return service.create(input, username);
    }

    @PutMapping("/{id}") public SemanticDimension update(HttpServletRequest request, @PathVariable Long id,
                                                         @Valid @RequestBody SemanticDimension input) {
        requireAccess(request, id, "update");
        return service.update(id, input);
    }

    @PostMapping("/{id}/validate") public Map<String, Object> validate(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "query");
        return service.validate(id);
    }

    @PostMapping("/{id}/publish") public SemanticDimension publish(HttpServletRequest request, @PathVariable Long id) {
        String username = requireAccess(request, id, "publish");
        return service.publish(id, username);
    }

    @PostMapping("/{id}/new-version") public SemanticDimension newVersion(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "update");
        return service.newVersion(id);
    }

    @PostMapping("/{id}/discard-draft") public SemanticDimension discardDraft(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "update");
        return service.discardDraft(id);
    }

    @PostMapping("/{id}/deprecate") public SemanticDimension deprecate(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "deprecate");
        return service.deprecate(id);
    }

    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        requireAccess(request, id, "delete");
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** 先取资源归属域（顺带 404），再做资源级判定。 */
    private String requireAccess(HttpServletRequest request, Long id, String action) {
        SemanticDimension dimension = service.require(id);
        return security.require(request, "dimension", id, action,
                domainCtx(dimension.getBusinessDomainId())).username();
    }

    private List<SemanticDimension> filteredList(HttpServletRequest request, List<SemanticDimension> dimensions) {
        String username = security.requireIdentity(request).username();
        return dimensions.stream()
                .filter(dimension -> security.isAllowed(username, "dimension", dimension.getId(), "view",
                        domainCtx(dimension.getBusinessDomainId())))
                .toList();
    }

    private static Map<String, String> domainCtx(Long domainId) {
        return domainId == null ? Map.of() : Map.of("domainId", String.valueOf(domainId));
    }

    @ExceptionHandler(SemanticDimensionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}