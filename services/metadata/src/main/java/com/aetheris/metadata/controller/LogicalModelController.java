package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.LogicalModel;
import com.aetheris.metadata.service.LogicalModelService;
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
 * 逻辑模型端点：
 * - GET    /api/metadata/logical-models         → 模型列表（可选 keyword 模糊匹配名称/编码）
 * - GET    /api/metadata/logical-models/{id}    → 模型详情
 * - POST   /api/metadata/logical-models         → 新增模型（admin）
 * - PUT    /api/metadata/logical-models/{id}    → 更新模型（admin）
 * - DELETE /api/metadata/logical-models/{id}    → 删除模型（admin）
 *
 * <p>entitiesJson 以 JSON 字符串提交（实体与关系描述），写操作要求 admin。
 */
@RestController
@RequestMapping("/api/metadata/logical-models")
public class LogicalModelController {

    private final LogicalModelService service;

    public LogicalModelController(LogicalModelService service) {
        this.service = service;
    }

    @GetMapping
    public List<LogicalModel> list(@RequestParam(required = false) String keyword) {
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public ResponseEntity<LogicalModel> getById(@PathVariable Long id) {
        LogicalModel model = service.getById(id);
        return model != null ? ResponseEntity.ok(model) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody LogicalModel model,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可创建逻辑模型"));
        }
        if (service.codeExists(model.getModelCode(), null)) {
            return ResponseEntity.status(409).body(Map.of("message", "模型编码已存在：" + model.getModelCode()));
        }
        model.setCreatedBy(username);
        return ResponseEntity.ok(service.create(model));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody LogicalModel model,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可修改逻辑模型"));
        }
        LogicalModel existing = service.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (service.codeExists(model.getModelCode(), id)) {
            return ResponseEntity.status(409).body(Map.of("message", "模型编码已存在：" + model.getModelCode()));
        }
        // 保留原始创建人与创建时间，其余字段以提交内容为准
        model.setId(id);
        model.setCreatedBy(existing.getCreatedBy());
        model.setCreatedAt(existing.getCreatedAt());
        boolean updated = service.update(model);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可删除逻辑模型"));
        }
        if (service.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}
