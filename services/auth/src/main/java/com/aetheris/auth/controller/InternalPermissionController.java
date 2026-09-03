package com.aetheris.auth.controller;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.dto.MutateCheckRequest;
import com.aetheris.auth.dto.PermissionCheckRequest;
import com.aetheris.auth.dto.PermissionCheckResponse;
import com.aetheris.auth.dto.SubjectRequest;
import com.aetheris.auth.dto.VisibilityRequest;
import com.aetheris.auth.dto.VisibilityResponse;
import com.aetheris.auth.service.PermissionDecisionService;
import com.aetheris.auth.service.PermissionVersionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 服务间权限判定端点（内部协议，网关不路由 /internal/**，且由
 * InternalEndpointInterceptor 校验共享内部令牌）。
 *
 * <p>判定输入只有认证后的 username + 资源/动作 + 服务端上下文，与浏览器
 * 传来的任何请求头无关：即便调用方携带 X-User-Role=admin 也不会放行。
 * 每次拒绝（含账号不存在、越权尝试）都异步写入权限审计事件。
 */
@RestController
@RequestMapping("/internal/permissions")
public class InternalPermissionController {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final PermissionDecisionService decisionService;
    private final PermissionVersionService versionService;
    private final PermissionAuditClient auditClient;

    public InternalPermissionController(
            PermissionDecisionService decisionService,
            PermissionVersionService versionService,
            PermissionAuditClient auditClient) {
        this.decisionService = decisionService;
        this.versionService = versionService;
        this.auditClient = auditClient;
    }

    @PostMapping("/check")
    public PermissionCheckResponse check(
            @Valid @RequestBody PermissionCheckRequest request,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        PermissionDecisionService.Decision decision = decisionService.check(
                request.username(), request.resourceType(), request.resourceId(),
                request.action(), request.ctx() == null ? Map.of() : request.ctx());
        if (!decision.allowed()) {
            auditDenied(request.username(), request.resourceType(),
                    request.resourceId() == null ? null : String.valueOf(request.resourceId()),
                    request.action(), decision.reason(), requestId);
        }
        return new PermissionCheckResponse(decision.allowed(), decision.reason());
    }

    @PostMapping("/may-mutate")
    public PermissionCheckResponse mayMutate(
            @Valid @RequestBody MutateCheckRequest request,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        PermissionDecisionService.Decision decision =
                decisionService.mayMutate(request.username(), request.service());
        if (!decision.allowed()) {
            auditDenied(request.username(), "service", request.service(), "mutate",
                    decision.reason(), requestId);
        }
        return new PermissionCheckResponse(decision.allowed(), decision.reason());
    }

    @PostMapping("/may-manage")
    public PermissionCheckResponse mayManage(
            @Valid @RequestBody SubjectRequest request,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        PermissionDecisionService.Decision decision = decisionService.mayManage(request.username());
        if (!decision.allowed()) {
            auditDenied(request.username(), "service", "manage", "admin",
                    decision.reason(), requestId);
        }
        return new PermissionCheckResponse(decision.allowed(), decision.reason());
    }

    @PostMapping("/visibility")
    public VisibilityResponse visibility(@Valid @RequestBody VisibilityRequest request) {
        PermissionDecisionService.Visibility visibility =
                decisionService.visibility(request.username(), request.resourceType(), request.action());
        return new VisibilityResponse(visibility.mode().name(), visibility.ids());
    }

    /** 权限版本：下游服务用版本号做本地缓存失效。 */
    @GetMapping("/version")
    public Map<String, Long> version() {
        return Map.of("version", versionService.current());
    }

    private void auditDenied(String username, String resourceType, String resourceId,
                             String action, String reason, String requestId) {
        auditClient.recordAsync(new PermissionEventRequest(
                username, null, resourceType, resourceId, action,
                "DENIED", reason, requestId), Duration.ofSeconds(2));
    }
}