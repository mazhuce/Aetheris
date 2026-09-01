package com.aetheris.serving.controller;

import com.aetheris.serving.dto.DashboardCreateRequest;
import com.aetheris.serving.dto.DashboardUpdateRequest;
import com.aetheris.serving.dto.DatasetInput;
import com.aetheris.serving.model.AnalyticsDataset;
import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.model.DashboardTemplate;
import com.aetheris.serving.repository.AnalyticsRepository;
import com.aetheris.serving.service.AnalyticsService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService service;
    private final AnalyticsRepository repository;

    public AnalyticsController(AnalyticsService service, AnalyticsRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @GetMapping("/datasets")
    public List<AnalyticsDataset> datasets() { return service.datasets(); }

    @GetMapping("/datasets/{id}")
    public AnalyticsDataset dataset(@PathVariable Long id) { return service.dataset(id); }

    @PostMapping("/datasets")
    public AnalyticsDataset createDataset(
            @Valid @RequestBody DatasetInput input,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.createDataset(input, username);
    }

    @PutMapping("/datasets/{id}")
    public AnalyticsDataset updateDataset(
            @PathVariable Long id,
            @Valid @RequestBody DatasetInput input,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.updateDataset(id, input);
    }

    @DeleteMapping("/datasets/{id}")
    public ResponseEntity<Void> deleteDataset(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        service.deleteDataset(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/datasets/{id}/query")
    public Map<String, Object> queryDataset(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return service.queryDataset(id, refresh);
    }

    @GetMapping("/dashboard-templates")
    public List<DashboardTemplate> templates() { return repository.listTemplates(); }

    @GetMapping("/dashboards")
    public List<Dashboard> dashboards() { return repository.listDashboards(); }

    @GetMapping("/dashboards/{id}")
    public Dashboard dashboard(@PathVariable Long id) { return service.dashboard(id); }

    @GetMapping("/dashboards/published/{slug}")
    public Dashboard published(@PathVariable String slug) { return service.published(slug); }

    @PostMapping("/dashboards")
    public Dashboard createDashboard(
            @Valid @RequestBody DashboardCreateRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.createDashboard(request, username);
    }

    @PutMapping("/dashboards/{id}")
    public Dashboard updateDashboard(
            @PathVariable Long id,
            @Valid @RequestBody DashboardUpdateRequest request,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.updateDashboard(id, request, username);
    }

    @PostMapping("/dashboards/{id}/publish")
    public Dashboard publish(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Name", defaultValue = "system") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        return service.publish(id, username);
    }

    @DeleteMapping("/dashboards/{id}")
    public ResponseEntity<Void> deleteDashboard(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        requireAdmin(role);
        service.deleteDashboard(id);
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(String role) {
        if (!"admin".equals(role)) throw new ForbiddenException();
    }

    public static class ForbiddenException extends RuntimeException { }

    @org.springframework.web.bind.annotation.ExceptionHandler(AnalyticsService.NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(AnalyticsService.NotFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", error.getMessage()));
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
