package com.aetheris.system.controller;

import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public List<AuditLog> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) Boolean success) {
        LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<AuditLog>()
                .like(StringUtils.hasText(username), AuditLog::getUsername, username)
                .eq(StringUtils.hasText(method), AuditLog::getMethod, method)
                .lt(Boolean.TRUE.equals(success), AuditLog::getStatusCode, 400)
                .ge(Boolean.FALSE.equals(success), AuditLog::getStatusCode, 400)
                .orderByDesc(AuditLog::getId)
                .last("LIMIT 200");
        return auditLogService.list(query);
    }
}
