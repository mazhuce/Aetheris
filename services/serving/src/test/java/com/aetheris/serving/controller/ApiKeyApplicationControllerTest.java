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
    void requestBindsApplicationToAuthenticatedUser() throws Exception {
        try(var f=new com.aetheris.serving.security.V2ServingFixture()){f.request(actual->{
        when(security.requireIdentity(actual)).thenReturn(new ServingIdentity(42L, "alice"));
        ApiKeyApplication application = new ApiKeyApplication(3L, "reporting", "analyst", "daily report", 30, null, "requested", null, null, null, OffsetDateTime.now());
        var trusted=com.aetheris.security.servlet.TrustedUser.require(actual);
        when(service.request(any(), eq(trusted))).thenReturn(application);

        assertEquals("analyst", controller.request(new ApiKeyApplicationRequest("reporting", "daily report", 30, null), actual).requester());
        verify(service).request(any(),eq(trusted));});}
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
        when(service.mine(2L)).thenReturn(List.of());

        assertEquals(0, controller.mine(request).size());
        verify(service).mine(2L);
        verify(security, never()).requireManage(request);
    }
}
