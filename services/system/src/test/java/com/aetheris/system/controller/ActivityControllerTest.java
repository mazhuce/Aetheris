package com.aetheris.system.controller;

import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.security.AetherisIdentity;
import com.aetheris.system.security.AuthPermissionClient;
import com.aetheris.system.security.SystemIdentityInterceptor;
import com.aetheris.system.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityControllerTest {

    @Test
    void viewerActivityIsUserScopedAndLimitIsClamped() {
        AuditLogService service = mock(AuditLogService.class);
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        ActivityController controller = new ActivityController(service);

        assertEquals(List.of(), controller.list("alice", "viewer", 999));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AuditLog>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(service).list(captor.capture());
        assertEquals(30, ActivityController.safeLimit(999));
        assertEquals(false, ActivityController.canViewAll("viewer"));
    }

    @Test
    void adminActivityIsGlobal() {
        AuditLogService service = mock(AuditLogService.class);
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        ActivityController controller = new ActivityController(service);

        controller.list("admin", "admin", 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<AuditLog>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(service).list(captor.capture());
        assertEquals(1, ActivityController.safeLimit(0));
        assertTrue(ActivityController.canViewAll("admin"));
    }

    @Test
    void httpActivityUsesLivePermissionInsteadOfRoleHeader() {
        AuditLogService service = mock(AuditLogService.class);
        AuthPermissionClient auth = mock(AuthPermissionClient.class);
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(auth.mayManage("alice")).thenReturn(false);
        ActivityController controller = new ActivityController(service, auth);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/activity");
        request.setAttribute(SystemIdentityInterceptor.IDENTITY_ATTRIBUTE,
                new AetherisIdentity(42L, "alice"));

        controller.list(request, 12);

        verify(auth).mayManage("alice");
    }
}
