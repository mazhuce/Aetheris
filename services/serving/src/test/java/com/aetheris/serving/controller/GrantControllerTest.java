package com.aetheris.serving.controller;

import com.aetheris.serving.dto.GrantInput;
import com.aetheris.serving.model.ServiceGrant;
import com.aetheris.serving.service.ServingGrantService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrantControllerTest {

    private final ServingGrantService service = mock(ServingGrantService.class);
    private final GrantController controller = new GrantController(service);

    private static final String ADMIN = "admin";
    private static final String VIEWER = "viewer";

    private ServiceGrant grant(Long id, String status) {
        return new ServiceGrant(id, "/api/serving/datasets/{id}/query", "数据分析师", "role", "read",
                null, status, "admin", null, null);
    }

    private GrantInput input() {
        return new GrantInput("/api/serving/datasets/{id}/query", "数据分析师", "role", "read", null, null);
    }

    @Test
    void listDelegatesToServiceWithStatusFilter() {
        when(service.grants(Optional.of("active"))).thenReturn(List.of(grant(1L, "active")));
        assertEquals(1, controller.list(Optional.of("active")).size());
    }

    @Test
    void listDelegatesToServiceWithoutStatusFilter() {
        when(service.grants(Optional.empty())).thenReturn(List.of());
        assertEquals(0, controller.list(Optional.empty()).size());
    }

    @Test
    void grantByIdDelegatesToService() {
        when(service.grant(1L)).thenReturn(grant(1L, "active"));
        assertEquals("数据分析师", controller.grant(1L).grantee());
    }

    @Test
    void createRequiresAdminRole() {
        assertThrows(GrantController.ForbiddenException.class,
                () -> controller.create(input(), "system", VIEWER));
    }

    @Test
    void createSucceedsForAdmin() {
        when(service.create(any(), anyString())).thenReturn(grant(1L, "active"));
        assertEquals("数据分析师", controller.create(input(), "admin", ADMIN).grantee());
    }

    @Test
    void updateRequiresAdminRole() {
        assertThrows(GrantController.ForbiddenException.class,
                () -> controller.update(1L, input(), VIEWER));
    }

    @Test
    void revokeRequiresAdminRole() {
        assertThrows(GrantController.ForbiddenException.class,
                () -> controller.revoke(1L, VIEWER));
    }

    @Test
    void revokeDelegatesToService() {
        when(service.revoke(1L)).thenReturn(grant(1L, "revoked"));
        assertEquals("revoked", controller.revoke(1L, ADMIN).status());
        verify(service).revoke(1L);
    }

    @Test
    void restoreRequiresAdminRole() {
        assertThrows(GrantController.ForbiddenException.class,
                () -> controller.restore(1L, VIEWER));
    }

    @Test
    void restoreDelegatesToService() {
        when(service.restore(1L)).thenReturn(grant(1L, "active"));
        assertEquals("active", controller.restore(1L, ADMIN).status());
        verify(service).restore(1L);
    }

    @Test
    void deleteRequiresAdminRole() {
        assertThrows(GrantController.ForbiddenException.class,
                () -> controller.delete(1L, VIEWER));
    }

    @Test
    void deleteReturns204() {
        ResponseEntity<Void> response = controller.delete(1L, ADMIN);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(1L);
    }

    @Test
    void notFoundExceptionHandlerReturns404() {
        ResponseEntity<Map<String, String>> response = controller.notFound(
                new ServingGrantService.NotFoundException("服务授权不存在"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("服务授权不存在", response.getBody().get("message"));
    }

    @Test
    void forbiddenExceptionHandlerReturns403() {
        ResponseEntity<Map<String, String>> response = controller.forbidden();
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().get("message").contains("管理员"));
    }

    @Test
    void badRequestExceptionHandlerReturns400() {
        ResponseEntity<Map<String, String>> response = controller.badRequest(
                new IllegalArgumentException("参数无效"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("参数无效", response.getBody().get("message"));
    }
}
