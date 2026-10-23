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
    private com.aetheris.metadata.service.VisibleListPager pager;
    @org.springframework.beans.factory.annotation.Autowired
    public void setVisiblePager(com.aetheris.metadata.service.VisibleListPager pager) { this.pager=pager; }
    @org.springframework.beans.factory.annotation.Value("${aetheris.list.compat-max-candidates:100000}") private int compatMaxCandidates=100000;
    @org.springframework.beans.factory.annotation.Value("${aetheris.list.compat-max-rows:20000}") private int compatMaxRows=20000;
    private DomainPermissionContext permissionContext;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPermissionContext(DomainPermissionContext context) { this.permissionContext = context; }
    private final MetricDefinitionService service;
    private final MetadataSecurity security;
    private final QueryAuditService auditService;
    private com.aetheris.metadata.service.MetadataTableService sourceTables;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSourceTables(com.aetheris.metadata.service.MetadataTableService tables) { this.sourceTables=tables; }

    public SemanticMetricController(MetricDefinitionService service, MetadataSecurity security, QueryAuditService auditService) {
        this.service = service;
        this.security = security;
        this.auditService = auditService;
    }

    @GetMapping public List<MetricDefinition> list(HttpServletRequest request,
                                                   @RequestParam(required = false) String keyword) {
        MetadataSecurity.Scope scope = security.visibilityStrict(request, "metric", "view");
        String username=security.requireIdentity(request).username();
        List<MetricDefinition> result=new java.util.ArrayList<>();int scanned=0;
        for(int page=1;;page++) {
            var published=service.published(keyword,scope.mode()==MetadataSecurity.ScopeMode.IDS?scope.ids():null,page,MetricDefinitionService.MAX_PAGE_SIZE);
            scanned+=published.size();if(scanned>compatMaxCandidates) throw new com.aetheris.metadata.service.VisibleListPager.ListLimitException();
            if(scope.mode()==MetadataSecurity.ScopeMode.OPAQUE) {
                var allowed=security.allowedIdsStrict(username,"metric","view",published.stream().collect(java.util.stream.Collectors.toMap(
                        MetricDefinition::getId,metric->domainCtx(metric.getBusinessDomainId()),(left,right)->left)));
                published=published.stream().filter(metric->allowed.contains(metric.getId())).toList();
            }
            result.addAll(published);if(result.size()>compatMaxRows) throw new com.aetheris.metadata.service.VisibleListPager.ListLimitException();
            // Count exhaustion before authorization; a denied page is not EOF.
            if(scanned<(long)page*MetricDefinitionService.MAX_PAGE_SIZE) return result;
            if(scanned>=compatMaxCandidates) throw new com.aetheris.metadata.service.VisibleListPager.ListLimitException();
        }
    }

    @GetMapping("/page") public com.aetheris.metadata.dto.VisibleListPage<MetricDefinition> page(HttpServletRequest request,
            @RequestParam(required=false) String keyword,@RequestParam(defaultValue="50") int size,@RequestParam(required=false) String cursor) {
        return pager.metrics(request,keyword,null,size,cursor,true);
    }

    @PostMapping("/{code}/query") public Map<String, Object> query(HttpServletRequest request, @PathVariable String code,
            @Valid @RequestBody(required = false) MetricQueryRequest queryRequest) {
        MetricDefinition metric = service.requirePublished(code);
        String username=security.require(request, "metric", metric.getId(), "query",
                domainCtx(metric.getBusinessDomainId())).username();
        long started = System.currentTimeMillis();
        try {
            Map<String,Object> result = service.queryPublished(metric, queryRequest);
            audit(username,metric,result,"success",started);
            return result;
        } catch (RuntimeException error) {
            audit(username,metric,null,"failed",started);
            if(error instanceof IllegalArgumentException) throw new IllegalArgumentException("指标查询参数无效");
            throw new MetricQueryUnavailableException();
        }
    }

    private void audit(String username,MetricDefinition metric,Map<String,Object> result,String status,long started) {
        try {
            var table=sourceTables==null || metric.getSourceTableId()==null?null:sourceTables.getById(metric.getSourceTableId());
            auditService.record("metric",metric.getMetricCode(),metric.getId(),table==null?null:table.getDatasourceId(),username,status,
                    result!=null && result.get("rowCount") instanceof Number n?n.longValue():null,System.currentTimeMillis()-started,false,
                    "success".equals(status)?null:"QUERY_FAILED",null,result==null?null:(String)result.get("generatedSql"));
        } catch(RuntimeException ignored) {
            // Audit persistence has its own failure counter; a completed query is not executed again or rewritten as failed.
        }
    }
    public static class MetricQueryUnavailableException extends RuntimeException { public MetricQueryUnavailableException() { super("指标查询暂时不可用"); } }
    @ExceptionHandler(MetricQueryUnavailableException.class)
    public ResponseEntity<Map<String,String>> queryUnavailable() {
        return ResponseEntity.status(502).body(Map.of("code","QUERY_UNAVAILABLE","message","指标查询暂时不可用"));
    }

    private Map<String, String> domainCtx(Long domainId) {
        return permissionContext == null
                ? (domainId == null ? Map.of() : Map.of("domainId", String.valueOf(domainId)))
                : permissionContext.of(domainId);
    }

    @ExceptionHandler(MetricDefinitionService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
    @ExceptionHandler(com.aetheris.metadata.service.VisibleListPager.ListLimitException.class)
    public ResponseEntity<Map<String,String>> listLimit(RuntimeException failure) {
        return ResponseEntity.status(503).body(Map.of("code","LIST_SCAN_LIMIT","message",failure.getMessage()));
    }
}
