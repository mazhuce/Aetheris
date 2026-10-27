package com.aetheris.governance.controller;

import com.aetheris.governance.entity.DataStandard;
import com.aetheris.governance.service.DataStandardService;
import com.aetheris.governance.security.GatewayIdentityInterceptor;
import com.aetheris.security.protocol.UserProof;
import com.aetheris.security.protocol.pagination.VisibleListCursor;
import com.aetheris.security.protocol.pagination.VisibleListPage;
import com.aetheris.security.protocol.pagination.VisibleListScanner;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashSet;
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
    private com.aetheris.governance.security.ResourceAuthorization authorization = new com.aetheris.governance.security.ResourceAuthorization();
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(com.aetheris.governance.security.ResourceAuthorization authorization) { this.authorization = authorization; }

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
        try {
            var page = page(keyword, status, standardType, 200, null);
            if (page.hasMore()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "列表超过兼容上限，请使用分页接口");
            return page.items();
        } catch (ResponseStatusException failure) {
            if (failure.getStatusCode() == HttpStatus.UNAUTHORIZED && RequestContextHolder.getRequestAttributes() == null) {
                return authorization.filter("data_standard", service.list(query), DataStandard::getId);
            }
            throw failure;
        }
    }

    /** Keyset page with the same filters as the compatibility list endpoint. */
    @GetMapping("/page")
    public VisibleListPage<DataStandard> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String standardType,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        String kw = keyword == null ? "" : keyword.trim();
        var trusted = authorization.currentUser();
        if (trusted == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要已验签用户");
        var proof = trusted.proof();
        String hash = pageHash("data_standard", proof, kw, status, standardType);
        long upper = maxId();
        return VisibleListScanner.page(cursor, size, hash, upper,
                (frozenUpper, before, limit) -> service.list(baseQuery(kw, status, standardType)
                        .le(DataStandard::getId, frozenUpper)
                        .lt(before != null, DataStandard::getId, before)
                        .orderByDesc(DataStandard::getId)
                        .last("LIMIT " + limit)),
                DataStandard::getId,
                rows -> new LinkedHashSet<>(authorization.filter("data_standard", rows, DataStandard::getId)
                        .stream().map(DataStandard::getId).toList()));
    }

    private LambdaQueryWrapper<DataStandard> baseQuery(String keyword, String status, String standardType) {
        return new LambdaQueryWrapper<DataStandard>()
                .and(keyword != null && !keyword.isBlank(), q -> q
                        .like(DataStandard::getStandardCode, keyword)
                        .or().like(DataStandard::getStandardName, keyword)
                        .or().like(DataStandard::getDefinition, keyword))
                .eq(status != null && !status.isBlank(), DataStandard::getStatus, status)
                .eq(standardType != null && !standardType.isBlank(), DataStandard::getStandardType, standardType);
    }

    private long maxId() {
        var row = service.getOne(new LambdaQueryWrapper<DataStandard>()
                .select(DataStandard::getId).orderByDesc(DataStandard::getId).last("LIMIT 1"));
        return row == null || row.getId() == null ? 0 : row.getId();
    }

    private static String pageHash(String kind, UserProof proof, Object... filters) {
        StringBuilder value = new StringBuilder(kind).append('\u0000')
                .append(proof.uid()).append('\u0000').append(proof.username()).append('\u0000')
                .append(proof.accessVersion());
        for (Object filter : filters) {
            if (filter == null) { value.append("\u0000N"); continue; }
            String text = String.valueOf(filter);
            value.append('\u0000').append('V').append(text.length()).append(':').append(text);
        }
        return VisibleListCursor.hash(value.toString());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DataStandard> getById(@PathVariable Long id) {
        authorization.require("data_standard", id, "view");
        DataStandard standard = service.getById(id);
        return standard != null ? ResponseEntity.ok(standard) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<?> create(
            @org.springframework.validation.annotation.Validated({jakarta.validation.groups.Default.class,
                    com.aetheris.governance.validation.CreateDefinition.class}) @RequestBody DataStandard standard,
            @RequestAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE) String username) {
        authorization.require("data_standard", null, "create");
        authorization.requireManage();
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
        authorization.require("data_standard", id, "update");
        authorization.protectDefinition(standard,service.getById(id),java.util.Set.of("standardName","status"));
        standard.setId(id);
        // 创建人与创建时间只在新建时确定，更新接口不接受改写
        standard.setCreatedBy(null);
        standard.setCreatedAt(null);
        standard.setUpdatedAt(OffsetDateTime.now());
        try {
            authorization.retainEditable(standard,java.util.Set.of("standardName","status"));
            boolean updated = service.updateById(standard);
            return updated ? ResponseEntity.ok(service.getById(id)) : ResponseEntity.notFound().build();
        } catch (DuplicateKeyException error) {
            return conflict(standard.getStandardCode());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id) {
        authorization.require("data_standard", id, "delete");
        boolean removed = service.removeById(id);
        return removed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private ResponseEntity<Map<String, String>> conflict(String standardCode) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", "标准编码 " + standardCode + " 已存在"));
    }

}
