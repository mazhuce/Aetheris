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

/** Compose 内部语义接口，供 serving 与 ai-agent 复用同一口径。 */
@RestController
@RequestMapping("/internal/semantic/metrics")
public class InternalSemanticMetricController {
    private DomainPermissionContext permissionContext;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPermissionContext(DomainPermissionContext context) { this.permissionContext = context; }
    private Map<String,String> domainCtx(Long id) {
        return permissionContext == null ? (id == null ? Map.of() : Map.of("domainId",id.toString())) : permissionContext.of(id);
    }
    private final MetricDefinitionService service;
    private final MetadataSecurity security;
    private final QueryAuditService auditService;
    private com.aetheris.metadata.service.MetadataTableService sourceTables;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSourceTables(com.aetheris.metadata.service.MetadataTableService tables){this.sourceTables=tables;}
    public InternalSemanticMetricController(MetricDefinitionService service,
                                            MetadataSecurity security,
                                            QueryAuditService auditService) {
        this.service = service;
        this.security = security;
        this.auditService = auditService;
    }
    @GetMapping public List<MetricDefinition> list(HttpServletRequest httpRequest,
            @RequestParam(required = false) String keyword) {
        String username = security.requireIdentity(httpRequest).username();
        List<MetricDefinition> metrics = service.published(keyword);
        MetadataSecurity.Scope scope = security.visibility(httpRequest, "metric", "view");
        return switch (scope.mode()) {
            case ALL -> metrics;
            case IDS -> metrics.stream().filter(metric -> metric.getBusinessDomainId() != null
                    && scope.ids().contains(metric.getBusinessDomainId())).toList();
            case OPAQUE -> metrics.stream().filter(metric -> security.isAllowed(username, "metric", metric.getId(),
                    "view", domainCtx(metric.getBusinessDomainId()))).toList();
        };
    }
    @PostMapping("/{code}/query") public Map<String, Object> query(HttpServletRequest httpRequest,
            @PathVariable String code, @Valid @RequestBody(required = false) MetricQueryRequest request) {
        MetricDefinition metric = service.requirePublished(code);
        String username = security.require(httpRequest, "metric", metric.getId(), "query",
                domainCtx(metric.getBusinessDomainId()))
                .username();
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> result = service.queryPublished(metric, request);
            audit(username, metric, result, "success", null, startedAt);
            return result;
        } catch (RuntimeException error) {
            audit(username, metric, null, "failed", error, startedAt);
            throw error;
        }
    }

    private void audit(String username, MetricDefinition metric, Map<String, Object> result,
                       String status, RuntimeException error, long startedAt) {
        try {
            Long rows = result != null && result.get("rowCount") instanceof Number n ? n.longValue() : null;
            String sql = result == null ? null : (String) result.get("generatedSql");
            var table=sourceTables==null||metric.getSourceTableId()==null?null:sourceTables.getById(metric.getSourceTableId());
            auditService.record("metric", metric.getMetricCode(), metric.getId(), table==null?null:table.getDatasourceId(),
                    username, status, rows, System.currentTimeMillis() - startedAt, false,
                    error == null ? null : error.getClass().getSimpleName(),
                    error == null ? null : error.getMessage(), sql);
        } catch (RuntimeException ignored) {
            // 审计是旁路能力，不能把查询本身改写成失败。
        }
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
