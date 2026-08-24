package com.aetheris.system.controller;

import com.aetheris.system.dto.AuditLogRequest;
import com.aetheris.system.entity.AuditLog;
import com.aetheris.system.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InternalAuditLogControllerTest {

    @Test
    void persistsGatewayAuditEvent() {
        AuditLogService service = mock(AuditLogService.class);
        InternalAuditLogController controller = new InternalAuditLogController(service);

        var response = controller.create(new AuditLogRequest(
                "admin", "admin", "POST", "/api/quality/rules", 201, 48L, "127.0.0.1"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(service).save(captor.capture());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("admin", captor.getValue().getUsername());
        assertEquals("/api/quality/rules", captor.getValue().getPath());
        assertEquals(201, captor.getValue().getStatusCode());
    }
}
