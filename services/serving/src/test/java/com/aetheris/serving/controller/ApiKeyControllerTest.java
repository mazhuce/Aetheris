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
    void createReturnsSecretOnceAndBindsOwnerToAuthenticatedOperator() throws Exception {
        try(var f=new com.aetheris.serving.security.V2ServingFixture()) { f.request(actual->{
        when(security.requireIdentity(actual)).thenReturn(new ServingIdentity(42L, "alice"));
        ApiKeySummary summary = new ApiKeySummary(5L, "abcd1234abcd", "reporting", "admin", "active", 60, null, null, OffsetDateTime.now(), null);
        var trusted=com.aetheris.security.servlet.TrustedUser.require(actual);
        when(service.create(any(), eq(trusted), eq("alice"))).thenReturn(new IssuedApiKey(summary, "ak_abcd1234abcd_secret"));

        IssuedApiKey result = controller.create(new ApiKeyCreateRequest("reporting", 60, null), actual);

        assertEquals("ak_abcd1234abcd_secret", result.secret());
        verify(security).requireManage(actual);
        verify(service).create(any(), eq(trusted), eq("alice"));
        });}
    }

    @Test
    void revokeRequiresManagementAndReturnsRedactedSummary() {
        when(service.revoke(5L)).thenReturn(new ApiKeySummary(5L, "abcd1234abcd", "reporting", "admin", "revoked", 60, null, null, OffsetDateTime.now(), OffsetDateTime.now()));

        assertEquals("revoked", controller.revoke(5L, request).status());
        verify(security).requireManage(request);
    }
}
