package com.aetheris.serving.controller;

import com.aetheris.serving.model.ApiUsageSummary;
import com.aetheris.serving.security.ServingSecurity;
import com.aetheris.serving.service.ApiUsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiUsageControllerTest {
    private final ApiUsageService service = mock(ApiUsageService.class);
    private final ServingSecurity security = mock(ServingSecurity.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final ApiUsageController controller = new ApiUsageController(service, security);

    @Test
    void summaryIsManagementOnlyAndBoundsTheRequestedWindow() {
        when(service.summary(eq("PUBLIC_METRIC"), isNull(), isNull(), eq(20))).thenReturn(List.of(
                new ApiUsageSummary("PUBLIC_METRIC", 100, 98, 2, .98, 14.2, 31.0, OffsetDateTime.now())));

        assertEquals(.98, controller.summary(request, "PUBLIC_METRIC", null, null, 20).get(0).successRate());
        verify(security).requireManage(request);
    }

    @Test
    void invalidAssetCodeIsRejectedBeforeQuery() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.summary(request, "bad-code", null, null, 20));
        verifyNoInteractions(service);
    }
}
