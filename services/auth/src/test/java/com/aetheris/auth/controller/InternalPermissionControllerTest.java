package com.aetheris.auth.controller;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.dto.MutateCheckRequest;
import com.aetheris.auth.dto.PermissionCheckRequest;
import com.aetheris.auth.dto.PermissionCheckResponse;
import com.aetheris.auth.dto.VisibilityRequest;
import com.aetheris.auth.dto.VisibilityResponse;
import com.aetheris.auth.service.PermissionDecisionService;
import com.aetheris.auth.service.PermissionVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalPermissionControllerTest {

    private PermissionDecisionService decisionService;
    private PermissionVersionService versionService;
    private PermissionAuditClient auditClient;
    private InternalPermissionController controller;

    @BeforeEach
    void setUp() {
        decisionService = mock(PermissionDecisionService.class);
        versionService = mock(PermissionVersionService.class);
        auditClient = mock(PermissionAuditClient.class);
        controller = new InternalPermissionController(decisionService, versionService, auditClient);
    }

    @Test
    @DisplayName("判定只依赖认证主体与受控上下文：请求头无法改变结果")
    void headersCannotGrantPermission() {
        when(decisionService.check(eq("alice"), eq("metric"), eq(3L), eq("create"), any()))
                .thenReturn(PermissionDecisionService.Decision.denied("没有 metric.create 权限"));

        // 调用方即使携带 X-User-Role=admin 之类的头，判定结果仍由决策引擎决定
        PermissionCheckResponse response = controller.check(
                new PermissionCheckRequest("alice", "metric", 3L, "create", Map.of("domainId", "5")),
                "req-1");

        assertFalse(response.allowed());
        assertEquals("没有 metric.create 权限", response.reason());
        verify(auditClient).recordAsync(any(PermissionEventRequest.class), any(Duration.class));
    }

    @Test
    @DisplayName("拒绝事件携带主体、资源、动作、决策与 request_id")
    void denialAuditCarriesContext() {
        when(decisionService.check(eq("alice"), eq("metric"), any(), eq("create"), any()))
                .thenReturn(PermissionDecisionService.Decision.denied("没有 metric.create 权限"));

        controller.check(
                new PermissionCheckRequest("alice", "metric", 3L, "create", Map.of()), "req-9");

        verify(auditClient).recordAsync(org.mockito.ArgumentMatchers.argThat(event ->
                "alice".equals(event.getUsername())
                        && "metric".equals(event.getResourceType())
                        && "3".equals(event.getResourceId())
                        && "create".equals(event.getAction())
                        && "DENIED".equals(event.getDecision())
                        && "req-9".equals(event.getRequestId())), any(Duration.class));
    }

    @Test
    @DisplayName("放行判定不产生审计事件（拒绝才审计）")
    void allowedDecisionNotAudited() {
        when(decisionService.check(eq("alice"), eq("metric"), any(), eq("query"), any()))
                .thenReturn(PermissionDecisionService.Decision.ALLOWED);

        PermissionCheckResponse response = controller.check(
                new PermissionCheckRequest("alice", "metric", null, "query", null), null);

        assertTrue(response.allowed());
        assertNull(response.reason());
        verify(auditClient, never()).recordAsync(any(PermissionEventRequest.class), any(Duration.class));
    }

    @Test
    @DisplayName("ctx 为空时注入空 Map 而不抛异常")
    void nullCtxIsTolerated() {
        when(decisionService.check(eq("alice"), eq("metric"), any(), eq("query"), eq(Map.of())))
                .thenReturn(PermissionDecisionService.Decision.ALLOWED);

        PermissionCheckResponse response = controller.check(
                new PermissionCheckRequest("alice", "metric", 9L, "query", null), null);
        assertTrue(response.allowed());
    }

    @Test
    @DisplayName("粗粒度写入口与系统管理入口的拒绝同样进入审计")
    void mutateAndManageDenialAudited() {
        when(decisionService.mayMutate("bob", "metadata"))
                .thenReturn(PermissionDecisionService.Decision.denied("没有 metadata 服务的写权限"));
        when(decisionService.mayManage("bob"))
                .thenReturn(PermissionDecisionService.Decision.denied("没有系统管理权限"));

        PermissionCheckResponse mutate = controller.mayMutate(
                new MutateCheckRequest("bob", "metadata"), "req-2");
        PermissionCheckResponse manage = controller.mayManage(
                new com.aetheris.auth.dto.SubjectRequest("bob"), "req-3");

        assertFalse(mutate.allowed());
        assertFalse(manage.allowed());
        verify(auditClient).recordAsync(org.mockito.ArgumentMatchers.argThat(event ->
                "service".equals(event.getResourceType()) && "metadata".equals(event.getResourceId())
                        && "DENIED".equals(event.getDecision())), any(Duration.class));
        verify(auditClient).recordAsync(org.mockito.ArgumentMatchers.argThat(event ->
                "service".equals(event.getResourceType()) && "manage".equals(event.getResourceId())
                        && "DENIED".equals(event.getDecision())), any(Duration.class));
    }

    @Test
    @DisplayName("可见性响应映射 mode 与 ids")
    void visibilityIsMapped() {
        when(decisionService.visibility("alice", "metric", "view"))
                .thenReturn(new PermissionDecisionService.Visibility(
                        PermissionDecisionService.Mode.IDS, List.of(5L, 6L)));

        VisibilityResponse response = controller.visibility(
                new VisibilityRequest("alice", "metric", "view"));

        assertEquals("IDS", response.mode());
        assertEquals(List.of(5L, 6L), response.ids());
    }

    @Test
    @DisplayName("权限版本号透出给下游做缓存失效")
    void versionIsExposed() {
        when(versionService.current()).thenReturn(42L);
        assertEquals(42L, controller.version().get("version"));
    }
}