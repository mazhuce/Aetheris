package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.BusinessDomain;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.BusinessDomainService;
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
@RequestMapping("/api/metadata/business-domains")
public class BusinessDomainController {
    private final BusinessDomainService service;
    private final MetadataSecurity security;

    public BusinessDomainController(BusinessDomainService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping
    public List<BusinessDomain> list(HttpServletRequest request,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status) {
        MetadataSecurity.Scope scope = security.visibility(request, "domain", "view");
        return switch (scope.mode()) {
            case ALL -> service.list(keyword, status);
            case IDS -> service.list(keyword, status, scope.ids());
            case OPAQUE -> filteredList(request, service.list(keyword, status));
        };
    }

    @GetMapping("/{id}")
    public BusinessDomain get(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "domain", id, "view", Map.of("domainId", String.valueOf(id)));
        return service.require(id);
    }

    @PostMapping
    public BusinessDomain create(HttpServletRequest request, @Valid @RequestBody BusinessDomain domain) {
        String username = security.require(request, "domain", null, "create", Map.of()).username();
        return service.create(domain, username);
    }

    @PutMapping("/{id}")
    public BusinessDomain update(HttpServletRequest request, @PathVariable Long id,
                                 @Valid @RequestBody BusinessDomain domain) {
        security.require(request, "domain", id, "update", Map.of("domainId", String.valueOf(id)));
        return service.update(id, domain);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "domain", id, "delete", Map.of("domainId", String.valueOf(id)));
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** OPAQUE（子资源级授权）时逐条判定可见性。 */
    private List<BusinessDomain> filteredList(HttpServletRequest request, List<BusinessDomain> domains) {
        String username = security.requireIdentity(request).username();
        java.util.Set<Long> allowed = security.allowedIds(username, "domain", "view",
                domains.stream().collect(java.util.stream.Collectors.toMap(BusinessDomain::getId,
                        domain -> Map.of("domainId", String.valueOf(domain.getId())), (left, right) -> left)));
        return domains.stream()
                .filter(domain -> allowed.contains(domain.getId()))
                .toList();
    }

    @ExceptionHandler(BusinessDomainService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
