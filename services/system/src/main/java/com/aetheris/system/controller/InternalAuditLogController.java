package com.aetheris.system.controller;

import com.aetheris.system.dto.AuditLogRequest;
import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import com.aetheris.system.security.SystemInternalRequests;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v2/audit/logs")
public class InternalAuditLogController {

    private final AuditLogService auditLogService;
    private final SystemInternalRequests bodies;

    public InternalAuditLogController(AuditLogService auditLogService,SystemInternalRequests bodies) {
        this.auditLogService = auditLogService;
        this.bodies = bodies;
    }

    @PostMapping
    public ResponseEntity<Void> create(HttpServletRequest http) {
        bodies.caller(http,"system.audit.record",Set.of("gateway"));
        AuditLogRequest request=bodies.read(http,AuditLogRequest.class);
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
