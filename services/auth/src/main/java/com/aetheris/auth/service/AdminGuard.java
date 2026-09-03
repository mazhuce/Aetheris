package com.aetheris.auth.service;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.web.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 管理接口守卫：资源级权限由 auth-service 作为源头判定（service.admin.manage），
 * 拒绝时抛 AccessDeniedException 并写入权限审计事件。
 */
@Component
public class AdminGuard {

    private final PermissionDecisionService decisionService;
    private final PermissionAuditClient auditClient;

    public AdminGuard(PermissionDecisionService decisionService, PermissionAuditClient auditClient) {
        this.decisionService = decisionService;
        this.auditClient = auditClient;
    }

    /** @throws AccessDeniedException 当主体没有系统管理权限 */
    public void requireManage(String username, String requestId) {
        PermissionDecisionService.Decision decision = decisionService.mayManage(username);
        if (!decision.allowed()) {
            String reason = decision.reason() == null ? "没有系统管理权限" : decision.reason();
            auditClient.recordAsync(new PermissionEventRequest(
                    username, null, "service", "manage", "admin",
                    "DENIED", reason, requestId), Duration.ofSeconds(2));
            throw new AccessDeniedException(reason);
        }
    }
}