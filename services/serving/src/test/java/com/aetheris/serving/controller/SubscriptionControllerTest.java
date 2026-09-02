package com.aetheris.serving.controller;

import com.aetheris.serving.dto.SubscriptionInput;
import com.aetheris.serving.model.DataSubscription;
import com.aetheris.serving.service.ServingSubscriptionService;
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

class SubscriptionControllerTest {

    private final ServingSubscriptionService service = mock(ServingSubscriptionService.class);
    private final SubscriptionController controller = new SubscriptionController(service);

    private static final String ADMIN = "admin";
    private static final String VIEWER = "viewer";

    private DataSubscription subscription(Long id) {
        return new DataSubscription(id, "核心交易明细日推送", "数据科学团队", "table", "dwd.orders_detail",
                "api", "每日 08:00", null, "active", "admin", null, null);
    }

    private SubscriptionInput input() {
        return new SubscriptionInput("核心交易明细日推送", "数据科学团队", "table", "dwd.orders_detail",
                "api", "每日 08:00", null, null);
    }

    @Test
    void listDelegatesToServiceWithStatusFilter() {
        when(service.subscriptions(Optional.of("active"))).thenReturn(List.of(subscription(1L)));
        assertEquals(1, controller.list(Optional.of("active")).size());
    }

    @Test
    void listDelegatesToServiceWithoutStatusFilter() {
        when(service.subscriptions(Optional.empty())).thenReturn(List.of());
        assertEquals(0, controller.list(Optional.empty()).size());
    }

    @Test
    void subscriptionByIdDelegatesToService() {
        when(service.subscription(1L)).thenReturn(subscription(1L));
        assertEquals("核心交易明细日推送", controller.subscription(1L).subscriptionName());
    }

    @Test
    void createRequiresAdminRole() {
        assertThrows(SubscriptionController.ForbiddenException.class,
                () -> controller.create(input(), "system", VIEWER));
    }

    @Test
    void createSucceedsForAdmin() {
        when(service.create(any(), anyString())).thenReturn(subscription(1L));
        assertEquals("核心交易明细日推送", controller.create(input(), "admin", ADMIN).subscriptionName());
    }

    @Test
    void updateRequiresAdminRole() {
        assertThrows(SubscriptionController.ForbiddenException.class,
                () -> controller.update(1L, input(), VIEWER));
    }

    @Test
    void deleteRequiresAdminRole() {
        assertThrows(SubscriptionController.ForbiddenException.class,
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
                new ServingSubscriptionService.NotFoundException("数据订阅不存在"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("数据订阅不存在", response.getBody().get("message"));
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
