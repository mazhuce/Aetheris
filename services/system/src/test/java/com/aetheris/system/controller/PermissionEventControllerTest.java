package com.aetheris.system.controller;

import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.service.PermissionEventService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionEventControllerTest {

    @Test
    void listsEventsWithFilters() {
        PermissionEventService service = mock(PermissionEventService.class);
        PermissionEvent event = new PermissionEvent();
        event.setUsername("alice");
        event.setDecision("DENIED");
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(event));
        PermissionEventController controller = new PermissionEventController(service);

        List<PermissionEvent> result = controller.list("alice", "DENIED", "metric", "query");

        assertEquals(1, result.size());
        assertEquals("DENIED", result.get(0).getDecision());
    }

    @Test
    void listsEventsWithoutFilters() {
        PermissionEventService service = mock(PermissionEventService.class);
        when(service.list(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        PermissionEventController controller = new PermissionEventController(service);

        assertEquals(0, controller.list(null, null, null, null).size());
    }
}