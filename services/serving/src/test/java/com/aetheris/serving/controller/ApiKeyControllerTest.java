package com.aetheris.serving.controller;

import com.aetheris.serving.dto.ApiKeyCreateRequest;
import com.aetheris.serving.model.ApiKeySummary;
import com.aetheris.serving.model.IssuedApiKey;
import com.aetheris.serving.security.ServingIdentity;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ApiKeyControllerTest {
    private final ApiKeyService service = mock(ApiKeyService.class);
    private final ServingSecurity security = mock(ServingSecurity.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ApiKeyController controller = new ApiKeyController(service, security);

    @Test
    void createReturnsSecretOnceAndBindsOwnerToAuthenticatedOperator() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "admin"));
        ApiKeySummary summary = new ApiKeySummary(5L, "abcd1234abcd", "reporting", "admin", "active", 60, null, null, OffsetDateTime.now(), null);
        when(service.create(any(), eq("admin"), eq("admin"))).thenReturn(new IssuedApiKey(summary, "ak_abcd1234abcd_secret"));

        IssuedApiKey result = controller.create(new ApiKeyCreateRequest("reporting", 60, null), request);

        assertEquals("ak_abcd1234abcd_secret", result.secret());
        verify(security).requireManage(request);
        verify(service).create(any(), eq("admin"), eq("admin"));
    }

    @Test
    void revokeRequiresManagementAndReturnsRedactedSummary() {
        when(service.revoke(5L)).thenReturn(new ApiKeySummary(5L, "abcd1234abcd", "reporting", "admin", "revoked", 60, null, null, OffsetDateTime.now(), OffsetDateTime.now()));

        assertEquals("revoked", controller.revoke(5L, request).status());
        verify(security).requireManage(request);
    }
}
