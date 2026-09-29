package com.aetheris.serving.controller;

import com.aetheris.serving.dto.ApiKeyApplicationRequest;
import com.aetheris.serving.model.ApiKeyApplication;
import com.aetheris.serving.model.IssuedApiKey;
import com.aetheris.serving.model.ApiKeySummary;
import com.aetheris.serving.security.ServingIdentity;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiKeyApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyApplicationControllerTest {
    private final ApiKeyApplicationService service = mock(ApiKeyApplicationService.class);
    private final ServingSecurity security = mock(ServingSecurity.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ApiKeyApplicationController controller = new ApiKeyApplicationController(service, security);

    @Test
    void requestBindsApplicationToAuthenticatedUser() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(2L, "analyst"));
        ApiKeyApplication application = new ApiKeyApplication(3L, "reporting", "analyst", "daily report", 30, null, "requested", null, null, null, OffsetDateTime.now());
        when(service.request(any(), eq("analyst"))).thenReturn(application);

        assertEquals("analyst", controller.request(new ApiKeyApplicationRequest("reporting", "daily report", 30, null), request).requester());
    }

    @Test
    void approvalIsManagementOnlyAndReturnsOneTimeSecret() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "admin"));
        ApiKeySummary summary = new ApiKeySummary(8L, "abcd1234abcd", "reporting", "analyst", "active", 30, null, null, OffsetDateTime.now(), null);
        when(service.approve(3L, "admin")).thenReturn(new IssuedApiKey(summary, "ak_abcd1234abcd_secret"));

        assertEquals("ak_abcd1234abcd_secret", controller.approve(3L, request).secret());
        verify(security).requireManage(request);
        verify(service).approve(3L, "admin");
    }

    @Test
    void mineDoesNotUseAdminList() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(2L, "analyst"));
        when(service.list(any())).thenReturn(List.of());

        assertEquals(0, controller.mine(request).size());
        verify(service).list(eq(java.util.Optional.of("analyst")));
        verify(security, never()).requireManage(request);
    }
}
