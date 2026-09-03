package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.MetricDefinitionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 面向看板、应用和人工调用的统一语义指标目录与查询接口。 */
@RestController
@RequestMapping("/api/metadata/semantic/metrics")
public class SemanticMetricController {
    private final MetricDefinitionService service;
    private final MetadataSecurity security;

    public SemanticMetricController(MetricDefinitionService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping public List<MetricDefinition> list(HttpServletRequest request,
                                                   @RequestParam(required = false) String keyword) {
        MetadataSecurity.Scope scope = security.visibility(request, "metric", "view");
        return switch (scope.mode()) {
            case ALL -> service.published(keyword);
            case IDS -> service.published(keyword, scope.ids());
            case OPAQUE -> {
                String username = security.requireIdentity(request).username();
                yield service.published(keyword).stream()
                        .filter(metric -> security.isAllowed(username, "metric", metric.getId(), "view",
                                domainCtx(metric.getBusinessDomainId())))
                        .toList();
            }
        };
    }

    @PostMapping("/{code}/query") public Map<String, Object> query(HttpServletRequest request, @PathVariable String code,
            @Valid @RequestBody(required = false) MetricQueryRequest queryRequest) {
        MetricDefinition metric = service.requirePublished(code);
        security.require(request, "metric", metric.getId(), "query",
                domainCtx(metric.getBusinessDomainId()));
        return service.query(code, queryRequest);
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