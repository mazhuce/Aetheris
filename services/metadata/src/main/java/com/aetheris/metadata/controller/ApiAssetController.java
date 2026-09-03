package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.ApiAssetService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API 资产登记端点（数据服务资源 data_service）：
 * - GET    /api/metadata/api-assets         → 资产列表（可选 keyword 模糊匹配名称/编码）
 * - GET    /api/metadata/api-assets/{id}    → 资产详情
 * - POST   /api/metadata/api-assets         → 登记资产
 * - PUT    /api/metadata/api-assets/{id}    → 更新资产
 * - DELETE /api/metadata/api-assets/{id}    → 删除资产
 *
 * <p>写操作经 auth-service 决策引擎做 data_service.create/update/delete 判定，
 * 创建人取网关验签身份（不再信任 X-User-Role 请求头）。
 */
@RestController
@RequestMapping("/api/metadata/api-assets")
public class ApiAssetController {

    private final ApiAssetService service;
    private final MetadataSecurity security;

    public ApiAssetController(ApiAssetService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping
    public List<ApiAsset> list(HttpServletRequest request, @RequestParam(required = false) String keyword) {
        security.require(request, "data_service", null, "view", Map.of());
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiAsset> getById(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "data_service", id, "view", Map.of());
        ApiAsset asset = service.getById(id);
        return asset != null ? ResponseEntity.ok(asset) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            HttpServletRequest request,
            @Valid @RequestBody ApiAsset asset) {
        String username = security.require(request, "data_service", null, "create", Map.of()).username();
        if (service.codeExists(asset.getAssetCode(), null)) {
            return ResponseEntity.status(409).body(Map.of("message", "资产编码已存在：" + asset.getAssetCode()));
        }
        asset.setCreatedBy(username);
        return ResponseEntity.ok(service.create(asset));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            HttpServletRequest request,
            @PathVariable Long id,
            @Valid @RequestBody ApiAsset asset) {
        security.require(request, "data_service", id, "update", Map.of());
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
            HttpServletRequest request,
            @PathVariable Long id) {
        security.require(request, "data_service", id, "delete", Map.of());
        if (service.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}