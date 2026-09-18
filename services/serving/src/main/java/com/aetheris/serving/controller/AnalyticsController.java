package com.aetheris.serving.controller;

import com.aetheris.serving.dto.DashboardCreateRequest;
import com.aetheris.serving.dto.DashboardUpdateRequest;
import com.aetheris.serving.dto.DatasetInput;
import com.aetheris.serving.model.AnalyticsDataset;
import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.model.DashboardTemplate;
import com.aetheris.serving.repository.AnalyticsRepository;
import com.aetheris.serving.service.AnalyticsService;
import com.aetheris.serving.security.ServingSecurity;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService service;
    private final AnalyticsRepository repository;
    private final ServingSecurity security;

    @Autowired
    public AnalyticsController(AnalyticsService service, AnalyticsRepository repository, ServingSecurity security) {
        this.service = service; this.repository = repository; this.security = security;
    }
    public AnalyticsController(AnalyticsService service, AnalyticsRepository repository) { this(service, repository, null); }
    public List<AnalyticsDataset> datasets() { return service.datasets(); }
    public AnalyticsDataset dataset(Long id) { return service.dataset(id); }
    public AnalyticsDataset createDataset(DatasetInput input, String username, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.createDataset(input, username); }
    public AnalyticsDataset updateDataset(Long id, DatasetInput input, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.updateDataset(id, input); }
    public ResponseEntity<Void> deleteDataset(Long id, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); service.deleteDataset(id); return ResponseEntity.noContent().build(); }
    public Map<String,Object> queryDataset(Long id, boolean refresh) { return service.queryDataset(id, refresh); }
    public List<DashboardTemplate> templates() { return repository.listTemplates(); }
    public List<Dashboard> dashboards() { return repository.listDashboards(); }
    public Dashboard dashboard(Long id) { return service.dashboard(id); }
    public Dashboard published(String slug) { return service.published(slug); }
    public Dashboard createDashboard(DashboardCreateRequest body, String username, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.createDashboard(body, username); }
    public Dashboard updateDashboard(Long id, DashboardUpdateRequest body, String username, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.updateDashboard(id, body, username); }
    public Dashboard publish(Long id, String username, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); return service.publish(id, username); }
    public ResponseEntity<Void> deleteDashboard(Long id, String role) { if (!"admin".equals(role)) throw new ForbiddenException(); service.deleteDashboard(id); return ResponseEntity.noContent().build(); }

    @GetMapping("/datasets")
    public List<AnalyticsDataset> datasets(HttpServletRequest request) { security.require(request,"dataset",null,"view"); return service.datasets(); }

    @GetMapping("/datasets/{id}")
    public AnalyticsDataset dataset(HttpServletRequest request,@PathVariable Long id) { security.require(request,"dataset",id,"view"); return service.dataset(id); }

    @PostMapping("/datasets")
    public AnalyticsDataset createDataset(
            @Valid @RequestBody DatasetInput input,
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String ignoredUsername,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        security.requireManage(request);
        return service.createDataset(input, security.requireIdentity(request).username());
    }

    @PutMapping("/datasets/{id}")
    public AnalyticsDataset updateDataset(
            @PathVariable Long id,
            @Valid @RequestBody DatasetInput input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        return service.updateDataset(id, input);
    }

    @DeleteMapping("/datasets/{id}")
    public ResponseEntity<Void> deleteDataset(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        service.deleteDataset(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/datasets/{id}/query")
    public Map<String, Object> queryDataset(
            HttpServletRequest request,@PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh) {
        security.require(request,"dataset",id,"query");
        return service.queryDataset(id, refresh);
    }

    @GetMapping("/dashboard-templates")
    public List<DashboardTemplate> templates(HttpServletRequest request) { security.require(request,"dataset",null,"view"); return repository.listTemplates(); }

    @GetMapping("/dashboards")
    public List<Dashboard> dashboards(HttpServletRequest request) { security.require(request,"dataset",null,"view"); return repository.listDashboards(); }

    @GetMapping("/dashboards/{id}")
    public Dashboard dashboard(HttpServletRequest request,@PathVariable Long id) { security.require(request,"dataset",id,"view"); return service.dashboard(id); }

    @GetMapping("/dashboards/published/{slug}")
    public Dashboard published(HttpServletRequest request,@PathVariable String slug) { security.require(request,"dataset",null,"view"); return service.published(slug); }

    @PostMapping("/dashboards")
    public Dashboard createDashboard(
            @Valid @RequestBody DashboardCreateRequest body,
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String ignoredUsername,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        security.requireManage(request);
        return service.createDashboard(body, security.requireIdentity(request).username());
    }

    @PutMapping("/dashboards/{id}")
    public Dashboard updateDashboard(
            @PathVariable Long id,
            @Valid @RequestBody DashboardUpdateRequest body,
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String ignoredUsername,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        security.requireManage(request);
        return service.updateDashboard(id, body, security.requireIdentity(request).username());
    }

    @PostMapping("/dashboards/{id}/publish")
    public Dashboard publish(
            @PathVariable Long id,
            HttpServletRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String ignoredUsername,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        security.requireManage(request);
        return service.publish(id, security.requireIdentity(request).username());
    }

    @DeleteMapping("/dashboards/{id}")
    public ResponseEntity<Void> deleteDashboard(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String ignoredRole,
            HttpServletRequest request) {
        security.requireManage(request);
        service.deleteDashboard(id);
        return ResponseEntity.noContent().build();
    }

    public static class ForbiddenException extends RuntimeException { }

    @org.springframework.web.bind.annotation.ExceptionHandler(ServingSecurity.UnauthorizedException.class)
    public ResponseEntity<Map<String, String>> unauthorized(ServingSecurity.UnauthorizedException error) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", error.getMessage())); }

    @org.springframework.web.bind.annotation.ExceptionHandler(ServingSecurity.ForbiddenException.class)
    public ResponseEntity<Map<String, String>> secureForbidden(ServingSecurity.ForbiddenException error) { return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", error.getMessage())); }

    @org.springframework.web.bind.annotation.ExceptionHandler(AnalyticsService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(AnalyticsService.NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AnalyticsService.PublishConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(AnalyticsService.PublishConflictException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "仅管理员可以修改数据集和看板"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }
}
