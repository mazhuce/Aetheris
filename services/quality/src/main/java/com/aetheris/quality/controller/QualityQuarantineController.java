package com.aetheris.quality.controller;

import com.aetheris.quality.entity.QualityQuarantineRecord;
import com.aetheris.quality.security.GatewayIdentityInterceptor;
import com.aetheris.quality.service.QualityQuarantineService;
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

/** 质量异常隔离区：默认只读，释放动作需要带身份的显式操作。 */
@RestController
@RequestMapping("/api/quality/quarantine")
public class QualityQuarantineController {
    private final QualityQuarantineService service;

    public QualityQuarantineController(QualityQuarantineService service) {
        this.service = service;
    }

    @GetMapping
    public List<QualityQuarantineRecord> list(
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "100") int limit) {
        return service.list(new LambdaQueryWrapper<QualityQuarantineRecord>()
                .eq(status != null && !status.isBlank(), QualityQuarantineRecord::getStatus, status)
                .orderByDesc(QualityQuarantineRecord::getCreatedAt)
                .last("LIMIT " + Math.clamp(limit, 1, 200)));
    }

    @PostMapping("/{id}/release")
    public ResponseEntity<?> release(
            @PathVariable Long id,
            @RequestAttribute(value = GatewayIdentityInterceptor.USERNAME_ATTRIBUTE, required = false)
            String username) {
        QualityQuarantineRecord record = service.getById(id);
        if (record == null) return ResponseEntity.notFound().build();
        if (!"open".equals(record.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "隔离记录已处理"));
        }
        record.setStatus("released");
        record.setReleasedAt(OffsetDateTime.now());
        record.setReleasedBy(username == null || username.isBlank() ? "unknown" : username);
        service.updateById(record);
        return ResponseEntity.ok(record);
    }
}
