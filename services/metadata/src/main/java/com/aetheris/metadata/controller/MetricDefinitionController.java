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
    private final SemanticAssetReviewService reviewService;

    public MetricDefinitionController(MetricDefinitionService service, MetadataSecurity security, SemanticAssetReviewService reviewService) {
        this.service = service;
        this.security = security;
        this.reviewService = reviewService;
    }

    @GetMapping public List<MetricDefinition> list(HttpServletRequest request,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "500") int size) {
        MetadataSecurity.Scope scope = security.visibilityStrict(request, "metric", "view");
        return switch (scope.mode()) {
            case ALL -> service.list(keyword, status, null, page, size);
            case IDS -> service.list(keyword, status, scope.ids(), page, size);
            case OPAQUE -> filteredList(request, keyword, status, page, size);
        };
    }

    @GetMapping("/page") public com.aetheris.metadata.dto.VisibleListPage<MetricDefinition> page(HttpServletRequest request,
            @RequestParam(required=false) String keyword,@RequestParam(required=false) String status,
            @RequestParam(defaultValue="50") int size,@RequestParam(required=false) String cursor) {
        return pager.metrics(request,keyword,status,size,cursor,"published".equals(status));
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

    private List<MetricDefinition> filteredList(HttpServletRequest request, String keyword,String status,
                                                int page, int size) {
        if (page < 1) throw new IllegalArgumentException("page 必须大于等于 1");
        if (size < 1 || size > MetricDefinitionService.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size 必须在 1 到 " + MetricDefinitionService.MAX_PAGE_SIZE + " 之间");
        }
        String username = security.requireIdentity(request).username();
        long end=(long)page*size,from=end-size;
        if(end>compatMaxRows) throw new com.aetheris.metadata.service.VisibleListPager.ListLimitException();
        List<MetricDefinition> result=new java.util.ArrayList<>();long visible=0;int scanned=0;
        for(int candidatePage=1;;candidatePage++) {
            var metrics=service.list(keyword,status,null,candidatePage,MetricDefinitionService.MAX_PAGE_SIZE);
            scanned+=metrics.size();if(scanned>compatMaxCandidates) throw new com.aetheris.metadata.service.VisibleListPager.ListLimitException();
            var allowed=security.allowedIdsStrict(username,"metric","view",metrics.stream().collect(java.util.stream.Collectors.toMap(
                    MetricDefinition::getId,metric->domainCtx(metric.getBusinessDomainId()),(left,right)->left)));
            for(var metric:metrics) if(allowed.contains(metric.getId())) {
                if(visible++>=from) result.add(metric);
                if(result.size()==size) return result;
            }
            if(metrics.size()<MetricDefinitionService.MAX_PAGE_SIZE) return result;
            if(scanned>=compatMaxCandidates) throw new com.aetheris.metadata.service.VisibleListPager.ListLimitException();
        }
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
