package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.DataProduct;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.DataProductService;
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
 * 数据产品端点（映射为数据集资源 dataset）：
 * - GET    /api/metadata/data-products         → 产品列表（可选 keyword 模糊匹配名称/编码）
 * - GET    /api/metadata/data-products/{id}    → 产品详情
 * - POST   /api/metadata/data-products         → 新增产品
 * - PUT    /api/metadata/data-products/{id}    → 更新产品
 * - DELETE /api/metadata/data-products/{id}    → 删除产品
 *
 * <p>写操作经 auth-service 决策引擎做 dataset.create/update/delete 判定，
 * 创建人取网关验签身份（不再信任 X-User-Role 请求头）。
 */
@RestController
@RequestMapping("/api/metadata/data-products")
public class DataProductController {

    private final DataProductService service;
    private final MetadataSecurity security;

    public DataProductController(DataProductService service, MetadataSecurity security) {
        this.service = service;
        this.security = security;
    }

    @GetMapping
    public List<DataProduct> list(HttpServletRequest request, @RequestParam(required = false) String keyword) {
        security.require(request, "dataset", null, "view", Map.of());
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataProduct> getById(HttpServletRequest request, @PathVariable Long id) {
        security.require(request, "dataset", id, "view", Map.of());
        DataProduct product = service.getById(id);
        return product != null ? ResponseEntity.ok(product) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            HttpServletRequest request,
            @Valid @RequestBody DataProduct product) {
        String username = security.require(request, "dataset", null, "create", Map.of()).username();
        if (service.codeExists(product.getProductCode(), null)) {
            return ResponseEntity.status(409).body(Map.of("message", "产品编码已存在：" + product.getProductCode()));
        }
        product.setCreatedBy(username);
        return ResponseEntity.ok(service.create(product));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            HttpServletRequest request,
            @PathVariable Long id,
            @Valid @RequestBody DataProduct product) {
        security.require(request, "dataset", id, "update", Map.of());
        DataProduct existing = service.getById(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (service.codeExists(product.getProductCode(), id)) {
            return ResponseEntity.status(409).body(Map.of("message", "产品编码已存在：" + product.getProductCode()));
        }
        // 保留原始创建人与创建时间，其余字段以提交内容为准
        product.setId(id);
        product.setCreatedBy(existing.getCreatedBy());
        product.setCreatedAt(existing.getCreatedAt());
        boolean updated = service.update(product);
        return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            HttpServletRequest request,
            @PathVariable Long id) {
        security.require(request, "dataset", id, "delete", Map.of());
        if (service.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}