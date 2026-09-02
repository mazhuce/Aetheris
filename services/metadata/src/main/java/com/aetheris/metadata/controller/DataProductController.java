package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.DataProduct;
import com.aetheris.metadata.service.DataProductService;
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
 * 数据产品端点：
 * - GET    /api/metadata/data-products         → 产品列表（可选 keyword 模糊匹配名称/编码）
 * - GET    /api/metadata/data-products/{id}    → 产品详情
 * - POST   /api/metadata/data-products         → 新增产品（admin）
 * - PUT    /api/metadata/data-products/{id}    → 更新产品（admin）
 * - DELETE /api/metadata/data-products/{id}    → 删除产品（admin）
 *
 * <p>写操作通过网关注入的 X-User-Role 头鉴权，创建人记入 created_by。
 */
@RestController
@RequestMapping("/api/metadata/data-products")
public class DataProductController {

    private final DataProductService service;

    public DataProductController(DataProductService service) {
        this.service = service;
    }

    @GetMapping
    public List<DataProduct> list(@RequestParam(required = false) String keyword) {
        return service.list(keyword);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataProduct> getById(@PathVariable Long id) {
        DataProduct product = service.getById(id);
        return product != null ? ResponseEntity.ok(product) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody DataProduct product,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可创建数据产品"));
        }
        if (service.codeExists(product.getProductCode(), null)) {
            return ResponseEntity.status(409).body(Map.of("message", "产品编码已存在：" + product.getProductCode()));
        }
        product.setCreatedBy(username);
        return ResponseEntity.ok(service.create(product));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody DataProduct product,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可修改数据产品"));
        }
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
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        if (!"admin".equals(role)) {
            return ResponseEntity.status(403).body(Map.of("message", "仅管理员可删除数据产品"));
        }
        if (service.getById(id) == null) {
            return ResponseEntity.notFound().build();
        }
        service.remove(id);
        return ResponseEntity.noContent().build();
    }
}
