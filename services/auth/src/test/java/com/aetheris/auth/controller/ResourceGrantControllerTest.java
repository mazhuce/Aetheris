package com.aetheris.auth.controller;

import com.aetheris.auth.dto.GrantRequest;
import com.aetheris.auth.dto.GrantView;
import com.aetheris.auth.model.DataScope;
import com.aetheris.auth.service.AdminGuard;
import com.aetheris.auth.service.RbacAdminService;
import com.aetheris.auth.web.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceGrantControllerTest {

    private final RbacAdminService service = mock(RbacAdminService.class);
    private final AdminGuard adminGuard = mock(AdminGuard.class);
    private final ResourceGrantController controller =
            new ResourceGrantController(service, adminGuard);

    @Test
    void missingRequesterHeaderIsRejectedBeforeAnyProcessing() {
        assertThrows(AccessDeniedException.class,
                () -> controller.list(null, null, null, null, null));
        verify(adminGuard, never()).requireManage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(service, never()).listGrants(null, null, null);
    }

    @Test
    void nonAdminCallerIsRejectedByGuardAndServiceIsNeverCalled() {
        var denied = new AccessDeniedException("没有系统管理权限");
        org.mockito.Mockito.doThrow(denied).when(adminGuard).requireManage("alice", null);

        assertThrows(AccessDeniedException.class,
                () -> controller.list("alice", null, null, null, null));
        verify(service, never()).listGrants(null, null, null);
    }

    @Test
    void adminCallerListsGrants() {
        when(service.listGrants("user", "metric", 5L))
                .thenReturn(List.of(new GrantView(1L, "user", 7L, "alice", "metric", 5L,
                        new DataScope("workspace", List.of("1")), List.of("view"),
                        true, "admin", null, null)));

        List<GrantView> result = controller.list("admin", null, "user", "metric", 5L);

        assertEquals(1, result.size());
        assertEquals("metric", result.get(0).resourceType());
        verify(adminGuard).requireManage("admin", null);
    }

    @Test
    void viewerCannotCreateGrantEvenWithValidBody() {
        var denied = new AccessDeniedException("没有系统管理权限");
        org.mockito.Mockito.doThrow(denied).when(adminGuard).requireManage("viewer", "req-1");

        GrantRequest request = new GrantRequest("user", 7L, "metric", 5L, null,
                List.of("view"), null);
        assertThrows(AccessDeniedException.class,
                () -> controller.create("viewer", "req-1", request));
        verify(service, never()).createGrant(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void viewercannotRevokeGrant() {
        var denied = new AccessDeniedException("没有系统管理权限");
        org.mockito.Mockito.doThrow(denied).when(adminGuard).requireManage("viewer", null);

        assertThrows(AccessDeniedException.class,
                () -> controller.revoke("viewer", null, 11L));
        verify(service, never()).revokeGrant(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void adminRevokeDeletesGrant() {
        var response = controller.revoke("admin", "req-2", 11L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).revokeGrant("admin", 11L);
    }
}