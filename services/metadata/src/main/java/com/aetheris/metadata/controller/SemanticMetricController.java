package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.MetricDefinitionService;
import com.aetheris.metadata.service.QueryAuditService;
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
    private final QueryAuditService auditService;

    public SemanticMetricController(MetricDefinitionService service, MetadataSecurity security, QueryAuditService auditService) {
        this.service = service;
        this.security = security;
        this.auditService = auditService;
    }

    @GetMapping public List<MetricDefinition> list(HttpServletRequest request,
                                                   @RequestParam(required = false) String keyword) {
        MetadataSecurity.Scope scope = security.visibility(request, "metric", "view");
        return switch (scope.mode()) {
            case ALL -> service.published(keyword);
            case IDS -> service.published(keyword, scope.ids());
            case OPAQUE -> {
                String username = security.requireIdentity(request).username();
                List<MetricDefinition> published = service.published(keyword);
                java.util.Set<Long> allowed = security.allowedIds(username, "metric", "view",
                        published.stream().collect(java.util.stream.Collectors.toMap(MetricDefinition::getId,
                                metric -> domainCtx(metric.getBusinessDomainId()), (left, right) -> left)));
                yield published.stream()
                        .filter(metric -> allowed.contains(metric.getId()))
                        .toList();
            }
        };
    }

    @PostMapping("/{code}/query") public Map<String, Object> query(HttpServletRequest request, @PathVariable String code,
            @Valid @RequestBody(required = false) MetricQueryRequest queryRequest) {
        MetricDefinition metric = service.requirePublished(code);
        security.require(request, "metric", metric.getId(), "query",
                domainCtx(metric.getBusinessDomainId()));
        long started = System.currentTimeMillis();
        try {
            Map<String,Object> result = service.query(code, queryRequest);
            auditService.record("metric", metric.getMetricCode(), metric.getId(), metric.getSourceTableId(), security.requireIdentity(request).username(), "success", result.get("rowCount") instanceof Number n ? n.longValue() : null, System.currentTimeMillis()-started, false, null, null, (String) result.get("generatedSql"));
            return result;
        } catch (RuntimeException error) {
            try { auditService.record("metric", metric.getMetricCode(), metric.getId(), metric.getSourceTableId(), security.requireIdentity(request).username(), "failed", null, System.currentTimeMillis()-started, false, error.getClass().getSimpleName(), error.getMessage(), null); } catch (Exception ignored) { }
            throw error;
        }
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
