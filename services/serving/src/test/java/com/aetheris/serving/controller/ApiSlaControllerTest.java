package com.aetheris.serving.controller;

import com.aetheris.serving.dto.ApiSlaPolicyRequest;
import com.aetheris.serving.model.ApiSlaEvaluation;
import com.aetheris.serving.model.ApiSlaPolicy;
import com.aetheris.serving.security.ServingIdentity;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiSlaService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiSlaControllerTest {
    private final ApiSlaService service = mock(ApiSlaService.class);
    private final ServingSecurity security = mock(ServingSecurity.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ApiSlaController controller = new ApiSlaController(service, security);

    @Test
    void policyUpdateRequiresManagementAndRecordsOperator() {
        when(security.requireIdentity(request)).thenReturn(new ServingIdentity(1L, "admin"));
        ApiSlaPolicy policy = new ApiSlaPolicy("PUBLIC_METRIC", new BigDecimal("0.990000"), 800, 30, "active", "admin", OffsetDateTime.now(), "admin", OffsetDateTime.now());
        when(service.upsert(eq("PUBLIC_METRIC"), any(), eq("admin"))).thenReturn(policy);

        assertEquals("active", controller.upsert("PUBLIC_METRIC", new ApiSlaPolicyRequest(new BigDecimal("0.99"), 800, 30, false), request).status());
        verify(security).requireManage(request);
        verify(service).upsert(eq("PUBLIC_METRIC"), any(), eq("admin"));
    }

    @Test
    void evaluationExposesComplianceAndBudget() {
        ApiSlaPolicy policy = new ApiSlaPolicy("PUBLIC_METRIC", new BigDecimal("0.990000"), 800, 30, "active", "admin", OffsetDateTime.now(), "admin", OffsetDateTime.now());
        ApiSlaEvaluation evaluation = new ApiSlaEvaluation(policy, OffsetDateTime.now().minusDays(1), OffsetDateTime.now(), 100, 1, .99, 700, 1, 0, true, true, true);
        when(service.evaluate(eq("PUBLIC_METRIC"), isNull(), isNull())).thenReturn(evaluation);

        assertEquals(0, controller.evaluation("PUBLIC_METRIC", null, null, request).remainingFailureBudget());
        verify(security).requireManage(request);
    }

    @Test
    void listIsManagementOnly() {
        when(service.list()).thenReturn(List.of());
        assertEquals(0, controller.list(request).size());
        verify(security).requireManage(request);
    }
}
