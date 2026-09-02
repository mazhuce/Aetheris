package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.service.ApiAssetService;
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
 * API 资产登记端点：
 * - GET    /api/metadata/api-assets         → 资产列表（可选 keyword 模糊匹配名称/编码）
 * - GET    /api/metadata/api-assets/{id}    → 资产详情
 * - POST   /api/metadata/api-assets         → 登记资产（admin）
 * - PUT    /api/metadata/api-assets/{id}    → 更新资产（admin）
 * - DELETE /api/metadata/api-assets/{id}    → 删除资产（admin）
 *
 * <p>写操作通过网关注入的 X-User-Role 头鉴权，创建人记入 created_by。
 */
@RestController
@RequestMapping("/api/metadata/api-assets")
public class ApiAssetController {

    private final ApiAssetService service;

    public ApiAssetController(ApiAssetService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiAsset> list(@RequestParam(required = false) String keyword) {
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiAsset> getById(@PathVariable Long id) {
        ApiAsset asset = service.getById(id);
        return asset != null ? ResponseEntity.ok(asset) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody ApiAsset asset,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可登记 API 资产"));
        }
        if (service.codeExists(asset.getAssetCode(), null)) {
            return ResponseEntity.status(409).body(Map.of("message", "资产编码已存在：" + asset.getAssetCode()));
        }
        asset.setCreatedBy(username);
        return ResponseEntity.ok(service.create(asset));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody ApiAsset asset,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可修改 API 资产"));
        }
        ApiAsset existing = service.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (service.codeExists(asset.getAssetCode(), id)) {
            return ResponseEntity.status(409).body(Map.of("message", "资产编码已存在：" + asset.getAssetCode()));
        }
        // 保留原始创建人与创建时间，其余字段以提交内容为准
        asset.setId(id);
        asset.setCreatedBy(existing.getCreatedBy());
        asset.setCreatedAt(existing.getCreatedAt());
        boolean updated = service.update(asset);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可删除 API 资产"));
        }
        if (service.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}
