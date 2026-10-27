package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityQuarantineRecord;
import com.aetheris.quality.security.GatewayIdentityInterceptor;
import com.aetheris.quality.service.QualityQuarantineService;
import com.aetheris.security.protocol.pagination.VisibleListCursor;
import com.aetheris.security.protocol.pagination.VisibleListPage;
import com.aetheris.security.protocol.pagination.VisibleListScanner;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Collectors;

/** 质量异常隔离区：默认只读，释放动作需要带身份的显式操作。 */
@RestController
@RequestMapping("/api/quality/quarantine")
public class QualityQuarantineController {
    private com.aetheris.quality.security.ResourceAuthorization authorization = new com.aetheris.quality.security.ResourceAuthorization();
    @org.springframework.beans.factory.annotation.Autowired
    public void setAuthorization(com.aetheris.quality.security.ResourceAuthorization authorization) { this.authorization = authorization; }
    private final QualityQuarantineService service;

    public QualityQuarantineController(QualityQuarantineService service) {
        this.service = service;
    }

    @GetMapping
    public List<QualityQuarantineRecord> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "100") int limit) {
        if (authorization.username() != null) {
            VisibleListPage<QualityQuarantineRecord> page = page(status, Math.clamp(limit, 1, 200), null);
            if (page.hasMore()) throw compatibilityLimit();
            return page.items();
        }
        return authorization.filter("quality_rule", service.list(new LambdaQueryWrapper<QualityQuarantineRecord>()
                .eq(status != null && !status.isBlank(), QualityQuarantineRecord::getStatus, status)
                .orderByDesc(QualityQuarantineRecord::getCreatedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200))), QualityQuarantineRecord::getRuleId);
    }

    @GetMapping("/page")
    public VisibleListPage<QualityQuarantineRecord> page(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String cursor) {
        var proof = authorization.currentUser().proof();
        String hash = hash("quality-quarantine", status, size, proof.uid(), proof.username(), proof.accessVersion());
        return VisibleListScanner.page(cursor, size, hash, upper(status),
                (upper, before, limit) -> service.list(new LambdaQueryWrapper<QualityQuarantineRecord>()
                        .eq(status != null && !status.isBlank(), QualityQuarantineRecord::getStatus, status)
                        .le(QualityQuarantineRecord::getId, upper)
                        .lt(before != null, QualityQuarantineRecord::getId, before)
                        .orderByDesc(QualityQuarantineRecord::getId)
                        .last("LIMIT " + limit)),
                QualityQuarantineRecord::getId,
                rows -> authorization.filter("quality_rule", rows, QualityQuarantineRecord::getRuleId)
                        .stream().map(QualityQuarantineRecord::getId).collect(Collectors.toSet()));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<?> release(
            @PathVariable Long id,
            @RequestAttribute(value = GatewayIdentityInterceptor.USERNAME_ATTRIBUTE, required = false)
            String username) {
        QualityQuarantineRecord record = service.getById(id);
        if (record == null) return ResponseEntity.notFound().build();
        authorization.require("quality_rule", record.getRuleId(), "update");
        if (!"open".equals(record.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "隔离记录已处理"));
        }
        record.setStatus("released");
        record.setReleasedAt(OffsetDateTime.now());
        record.setReleasedBy(username == null || username.isBlank() ? "unknown" : username);
        service.updateById(record);
        return ResponseEntity.ok(record);
    }

    private long upper(String status) {
        return service.list(new LambdaQueryWrapper<QualityQuarantineRecord>()
                .select(QualityQuarantineRecord::getId)
                .eq(status != null && !status.isBlank(), QualityQuarantineRecord::getStatus, status)
                .orderByDesc(QualityQuarantineRecord::getId).last("LIMIT 1"))
                .stream().map(QualityQuarantineRecord::getId).filter(java.util.Objects::nonNull).findFirst().orElse(0L);
    }

    private static String hash(Object... parts) {
        String canonical = Arrays.stream(parts).map(value -> {
            String text = value == null ? "<null>" : String.valueOf(value);
            return text.length() + ":" + text;
        }).collect(Collectors.joining("|"));
        return VisibleListCursor.hash(canonical);
    }

    private static org.springframework.web.server.ResponseStatusException compatibilityLimit() {
        return new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "列表超过兼容数组上限，请使用分页接口");
    }
}
