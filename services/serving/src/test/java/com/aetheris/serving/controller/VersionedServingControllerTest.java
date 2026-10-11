package com.aetheris.serving.controller;

import com.aetheris.serving.security.ServingIdentity;
import com.aetheris.serving.security.ServiceIdentitySigner;
import com.aetheris.serving.security.ServingRateLimiter;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.MetadataQueryClient;
import com.aetheris.serving.service.ApiKeyService;
import com.aetheris.serving.service.ApiUsageService;
import com.aetheris.serving.service.DistributedApiRateLimiter;
import com.aetheris.serving.dto.ApiAssetQueryRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionedServingControllerTest {
    private final MetadataQueryClient metadata = mock(MetadataQueryClient.class);
    private final ServingSecurity security = mock(ServingSecurity.class);
    private final ServingRateLimiter rateLimiter = mock(ServingRateLimiter.class);
    private final VersionedServingController controller = new VersionedServingController(metadata, security, rateLimiter);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void executesPublishedMetricAssetOnlyAfterAssetPermission() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "alice"));
        when(request.getHeader("X-Aetheris-Identity")).thenReturn("signed-alice");
        when(metadata.publishedApiAsset("PUBLIC_METRIC")).thenReturn(Map.of(
                "id", 7L, "targetType", "metric", "targetRef", "GMV_DAILY", "version", "v1"));
        when(rateLimiter.allow("alice", "api:PUBLIC_METRIC")).thenReturn(true);
        when(metadata.queryMetric(eq("GMV_DAILY"), any(), any())).thenReturn(Map.of("rowCount", 1));

        Map<String, Object> result = controller.apiAsset(request, "PUBLIC_METRIC",
                new ApiAssetQueryRequest(List.of("region"), "day", "2026-01-01", "2026-01-31", List.of(), 20));

        assertEquals(1, result.get("rowCount"));
        verify(security).require(request, "data_service", 7L, "execute");
        verify(metadata).queryMetric(eq("GMV_DAILY"), any(), eq("signed-alice"));
    }

    @Test
    void executesPublishedTableAssetWithOnlyColumnContract() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "alice"));
        when(metadata.publishedApiAsset("PUBLIC_TABLE")).thenReturn(Map.of(
                "id", 8L, "targetType", "table", "targetRef", "42", "version", "v1"));
        when(rateLimiter.allow("alice", "api:PUBLIC_TABLE")).thenReturn(true);
        when(metadata.queryTable(eq(42L), any(), any())).thenReturn(Map.of("rowCount", 2));

        Map<String, Object> result = controller.apiAsset(request, "PUBLIC_TABLE",
                new ApiAssetQueryRequest(List.of(), null, null, null, List.of("id", "name"), 10));

        assertEquals(2, result.get("rowCount"));
        verify(metadata).queryTable(eq(42L), any(), any());
    }

    @Test
    void missingPublishedAssetMapsToNotFound() {
        ResponseEntity<Map<String, String>> response = controller.apiAssetNotFound(
                new MetadataQueryClient.ApiAssetNotFoundException());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void apiKeyIdentityIsBoundToOwnerAndQuotaBeforePermissionCheck() {
        ApiKeyService apiKeys = mock(ApiKeyService.class);
        VersionedServingController securedController = new VersionedServingController(metadata, security, rateLimiter,
                apiKeys, null, null, new ServiceIdentitySigner("01234567890123456789012345678901"));
        when(request.getHeader("X-API-Key")).thenReturn("ak_prefix_secret");
        when(request.getHeader("X-Aetheris-Identity")).thenReturn("forged-gateway-identity");
        when(apiKeys.authenticate("ak_prefix_secret")).thenReturn(java.util.Optional.of(
                new ApiKeyService.ApiKeyPrincipal(11L, "partner-a", "hash", 10)));
        when(apiKeys.consumeQuota(11L)).thenReturn(true);
        when(metadata.publishedApiAsset("PUBLIC_METRIC")).thenReturn(Map.of(
                "id", 7L, "targetType", "metric", "targetRef", "GMV_DAILY", "version", "v1"));
        when(rateLimiter.allow("partner-a", "api:PUBLIC_METRIC")).thenReturn(true);
        when(metadata.queryMetric(eq("GMV_DAILY"), any(), any())).thenReturn(Map.of("rowCount", 1));

        assertEquals(1, securedController.apiAsset(request, "PUBLIC_METRIC", null).get("rowCount"));
        verify(apiKeys).consumeQuota(11L);
        verify(security).require(request, "data_service", 7L, "execute");
        verify(metadata).queryMetric(eq("GMV_DAILY"), any(), org.mockito.ArgumentMatchers.argThat(value ->
                value != null && !value.isBlank() && !value.equals("forged-gateway-identity")));
    }

    @Test
    void recordsUsageWithoutChangingSuccessfulResponse() {
        ApiUsageService usage = mock(ApiUsageService.class);
        VersionedServingController observedController = new VersionedServingController(metadata, security, rateLimiter, null, usage);
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "alice"));
        when(metadata.publishedApiAsset("PUBLIC_METRIC")).thenReturn(Map.of(
                "id", 7L, "targetType", "metric", "targetRef", "GMV_DAILY", "version", "v1"));
        when(rateLimiter.allow("alice", "api:PUBLIC_METRIC")).thenReturn(true);
        when(metadata.queryMetric(eq("GMV_DAILY"), any(), any())).thenReturn(Map.of("rowCount", 1));

        assertEquals(1, observedController.apiAsset(request, "PUBLIC_METRIC", null).get("rowCount"));
        verify(usage).record(eq("PUBLIC_METRIC"), eq("alice"), eq("identity"), eq(200), anyLong(), isNull());
    }

    @Test
    void identityCallsUseSharedRateWindowButApiKeysUseTheirOwnQuota() {
        DistributedApiRateLimiter distributed = mock(DistributedApiRateLimiter.class);
        VersionedServingController limitedController = new VersionedServingController(metadata, security, rateLimiter, null, null, distributed);
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "alice"));
        when(metadata.publishedApiAsset("PUBLIC_METRIC")).thenReturn(Map.of(
                "id", 7L, "targetType", "metric", "targetRef", "GMV_DAILY", "version", "v1"));
        when(rateLimiter.allow("alice", "api:PUBLIC_METRIC")).thenReturn(true);
        when(distributed.allow("alice", "PUBLIC_METRIC")).thenReturn(false);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> limitedController.apiAsset(request, "PUBLIC_METRIC", null));
        verify(distributed).allow("alice", "PUBLIC_METRIC");
        verify(metadata, never()).queryMetric(eq("GMV_DAILY"), any(), any());
    }
}
