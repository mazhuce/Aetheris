package com.aetheris.system.controller;

import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工作台活动流：管理员查看全局写操作，普通用户只查看自己的操作。 */
@RestController
@RequestMapping("/api/system/activity")
public class ActivityController {
    private final AuditLogService auditLogService;

    public ActivityController(AuditLogService auditLogService) { this.auditLogService = auditLogService; }

    @GetMapping
    public List<AuditLog> list(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role,
            @RequestParam(defaultValue = "12") int limit) {
        int safeLimit = safeLimit(limit);
        return auditLogService.list(new LambdaQueryWrapper<AuditLog>()
                .eq(!canViewAll(role), AuditLog::getUsername, username)
                .ne(AuditLog::getPath, "/api/auth/login")
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + safeLimit));
    }

    static int safeLimit(int requested) { return Math.clamp(requested, 1, 30); }
    static boolean canViewAll(String role) { return "admin".equals(role); }
}
