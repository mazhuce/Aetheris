package com.aetheris.system.controller;

import com.aetheris.system.dto.AuditLogRequest;
import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.service.AuditLogService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/audit/logs")
public class InternalAuditLogController {

    private final AuditLogService auditLogService;

    public InternalAuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody AuditLogRequest request) {
        AuditLog log = new AuditLog();
        log.setUsername(request.username());
        log.setUserRole(request.userRole());
        log.setMethod(request.method());
        log.setPath(request.path());
        log.setStatusCode(request.statusCode());
        log.setDurationMs(request.durationMs());
        log.setClientIp(request.clientIp());
        auditLogService.save(log);
        return ResponseEntity.accepted().build();
    }
}
