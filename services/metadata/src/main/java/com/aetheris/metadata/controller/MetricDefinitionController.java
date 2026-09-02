package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.service.MetricDefinitionService;
import jakarta.validation.Valid;
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

/**
 * 指标定义端点：
 * - GET    /api/metadata/metric-definitions         → 指标列表（可选 keyword 模糊匹配名称/编码）
 * - GET    /api/metadata/metric-definitions/{id}    → 指标详情
 * - POST   /api/metadata/metric-definitions         → 新增指标（admin）
 * - PUT    /api/metadata/metric-definitions/{id}    → 更新指标（admin）
 * - DELETE /api/metadata/metric-definitions/{id}    → 删除指标（admin）
 *
 * <p>写操作通过网关注入的 X-User-Role 头鉴权，创建人记入 created_by。
 */
@RestController
@RequestMapping("/api/metadata/metric-definitions")
public class MetricDefinitionController {

    private final MetricDefinitionService service;

    public MetricDefinitionController(MetricDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<MetricDefinition> list(@RequestParam(required = false) String keyword) {
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MetricDefinition> getById(@PathVariable Long id) {
        MetricDefinition metric = service.getById(id);
        return metric != null ? ResponseEntity.ok(metric) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody MetricDefinition metric,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可创建指标定义"));
        }
        if (service.codeExists(metric.getMetricCode(), null)) {
            return ResponseEntity.status(409).body(Map.of("message", "指标编码已存在：" + metric.getMetricCode()));
        }
        metric.setCreatedBy(username);
        return ResponseEntity.ok(service.create(metric));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody MetricDefinition metric,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可修改指标定义"));
        }
        MetricDefinition existing = service.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (service.codeExists(metric.getMetricCode(), id)) {
            return ResponseEntity.status(409).body(Map.of("message", "指标编码已存在：" + metric.getMetricCode()));
        }
        // 保留原始创建人与创建时间，其余字段以提交内容为准
        metric.setId(id);
        metric.setCreatedBy(existing.getCreatedBy());
        metric.setCreatedAt(existing.getCreatedAt());
        boolean updated = service.update(metric);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可删除指标定义"));
        }
        if (service.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}
