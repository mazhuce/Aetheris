package com.aetheris.system.controller;

import com.aetheris.system.dto.PermissionEventRequest;
import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.service.PermissionEventService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InternalPermissionEventControllerTest {

    @Test
    void persistsDeniedPermissionEvent() {
        PermissionEventService service = mock(PermissionEventService.class);
        InternalPermissionEventController controller = new InternalPermissionEventController(service);

        var response = controller.create(new PermissionEventRequest(
                "alice", 42L, "metric", "5", "query", "DENIED", "没有 metric.query 权限", "req-1"));

        ArgumentCaptor<PermissionEvent> captor = ArgumentCaptor.forClass(PermissionEvent.class);
        verify(service).save(captor.capture());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("alice", captor.getValue().getUsername());
        assertEquals(42L, captor.getValue().getUserId());
        assertEquals("DENIED", captor.getValue().getDecision());
        assertEquals("req-1", captor.getValue().getRequestId());
    }

    @Test
    void persistsGratedEventWithoutOptionalFields() {
        PermissionEventService service = mock(PermissionEventService.class);
        InternalPermissionEventController controller = new InternalPermissionEventController(service);

        controller.create(new PermissionEventRequest(
                null, null, "role", "9", "create", "GRANTED", null, null));

        ArgumentCaptor<PermissionEvent> captor = ArgumentCaptor.forClass(PermissionEvent.class);
        verify(service).save(captor.capture());
        assertNull(captor.getValue().getUsername());
        assertEquals("GRANTED", captor.getValue().getDecision());
    }
}