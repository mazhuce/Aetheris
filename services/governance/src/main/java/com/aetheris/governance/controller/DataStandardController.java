package com.aetheris.governance.controller;

import com.aetheris.governance.entity.DataStandard;
import com.aetheris.governance.service.DataStandardService;
import com.aetheris.governance.security.GatewayIdentityInterceptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.validation.Valid;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据标准端点：
 * - GET    /api/governance/standards         → 标准列表（keyword/status/standardType 可选过滤）
 * - GET    /api/governance/standards/{id}    → 标准详情
 * - POST   /api/governance/standards         → 新建标准（admin）
 * - PUT    /api/governance/standards/{id}    → 更新标准（admin）
 * - DELETE /api/governance/standards/{id}    → 删除标准（admin）
 *
 * <p>standard_code 有唯一约束，重复保存返回 409 而非 500，方便前端提示。
 */
@RestController
@RequestMapping("/api/governance/standards")
public class DataStandardController {

    private final DataStandardService service;

    public DataStandardController(DataStandardService service) {
        this.service = service;
    }

    /** 标准列表：keyword 匹配编码/名称/定义，status 与 standardType 精确匹配。 */
    @GetMapping
    public List<DataStandard> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String standardType) {
        String kw = keyword == null ? "" : keyword.trim();
        LambdaQueryWrapper<DataStandard> query = new LambdaQueryWrapper<DataStandard>()
                .and(!kw.isBlank(), q -> q
                        .like(DataStandard::getStandardCode, kw)
                        .or().like(DataStandard::getStandardName, kw)
                        .or().like(DataStandard::getDefinition, kw))
                .eq(status != null && !status.isBlank(), DataStandard::getStatus, status)
                .eq(standardType != null && !standardType.isBlank(), DataStandard::getStandardType, standardType)
                .orderByDesc(DataStandard::getId);
        return service.list(query);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataStandard> getById(@PathVariable Long id) {
        DataStandard standard = service.getById(id);
        return standard != null ? ResponseEntity.ok(standard) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody DataStandard standard,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        standard.setId(null);
        standard.setCreatedBy(username);
        try {
            service.save(standard);
        } catch (DuplicateKeyException error) {
            return conflict(standard.getStandardCode());
        }
        return ResponseEntity.ok(standard);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable Long id,
            @Valid @RequestBody DataStandard standard) {
        standard.setId(id);
        // 创建人与创建时间只在新建时确定，更新接口不接受改写
        standard.setCreatedBy(null);
        standard.setCreatedAt(null);
        standard.setUpdatedAt(OffsetDateTime.now());
        try {
            boolean updated = service.updateById(standard);
            return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
        } catch (DuplicateKeyException error) {
            return conflict(standard.getStandardCode());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id) {
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private ResponseEntity<Map<String, String>> conflict(String standardCode) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "标准编码 " + standardCode + " 已存在"));
    }

}
