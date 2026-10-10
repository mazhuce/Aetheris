package com.aetheris.system.controller;

import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.service.AuditLogService;
import com.aetheris.system.security.AetherisIdentity;
import com.aetheris.system.security.AuthPermissionClient;
import com.aetheris.system.security.SystemIdentityInterceptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工作台活动流：管理员查看全局写操作，普通用户只查看自己的操作。 */
@RestController
@RequestMapping("/api/system/activity")
public class ActivityController {
    private final AuditLogService auditLogService;
    private final AuthPermissionClient authPermissionClient;

    @Autowired
    public ActivityController(AuditLogService auditLogService,
                              AuthPermissionClient authPermissionClient) {
        this.auditLogService = auditLogService;
        this.authPermissionClient = authPermissionClient;
    }

    /** Test-friendly constructor; production wiring uses the dynamic auth check. */
    public ActivityController(AuditLogService auditLogService) {
        this(auditLogService, null);
    }

    @GetMapping
    public List<AuditLog> list(HttpServletRequest request,
                               @RequestParam(defaultValue = "12") int limit) {
        AetherisIdentity identity = (AetherisIdentity) request.getAttribute(
                SystemIdentityInterceptor.IDENTITY_ATTRIBUTE);
        if (identity == null) {
            throw new IllegalStateException("缺少有效的系统身份");
        }
        boolean canViewAll = authPermissionClient != null
                && authPermissionClient.mayManage(identity.username());
        return listForScope(identity.username(), canViewAll, limit);
    }

    /** Kept for focused unit tests and non-HTTP callers. */
    public List<AuditLog> list(
            String username, String role, int limit) {
        return listForScope(username, canViewAll(role), limit);
    }

    private List<AuditLog> listForScope(String username, boolean canViewAll, int limit) {
        int safeLimit = safeLimit(limit);
        return auditLogService.list(new LambdaQueryWrapper<AuditLog>()
                .eq(!canViewAll, AuditLog::getUsername, username)
                .ne(AuditLog::getPath, "/api/auth/login")
                .orderByDesc(AuditLog::getId)
                .last("LIMIT " + safeLimit));
    }

    static int safeLimit(int requested) { return Math.clamp(requested, 1, 30); }
    static boolean canViewAll(String role) { return "admin".equals(role); }
}
