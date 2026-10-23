package com.aetheris.serving.controller;

import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.repository.AnalyticsRepository;
import com.aetheris.serving.service.AnalyticsService;
import com.aetheris.serving.security.ServingSecurity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsPermissionContractTest {
    @Test void datasetGrantDoesNotAuthorizeTheSameNumericDashboardId() {
        var service=mock(AnalyticsService.class);
        var security=mock(ServingSecurity.class);
        var request=new MockHttpServletRequest();
        doThrow(new ServingSecurity.ForbiddenException("denied")).when(security).require(request,"dashboard",8L,"view");
        var controller=new AnalyticsController(service,mock(AnalyticsRepository.class),security);
        assertThrows(ServingSecurity.ForbiddenException.class,()->controller.dashboard(request,8L));
        verify(service,never()).dashboard(8L);
    }
    @Test void publishedSlugAuthorizesResolvedDashboardId() {
        var service=mock(AnalyticsService.class);
        var security=mock(ServingSecurity.class);
        var request=new MockHttpServletRequest();
        Dashboard dashboard=mock(Dashboard.class);
        when(dashboard.id()).thenReturn(8L);when(service.published("sales")).thenReturn(dashboard);
        assertSame(dashboard,new AnalyticsController(service,mock(AnalyticsRepository.class),security).published(request,"sales"));
        verify(security).require(request,"dashboard",8L,"view");
        verify(security,never()).require(request,"dataset",null,"view");
    }
    @Test void publishedDatasetRequiresItsOwnPublishAction() {
        var security=mock(ServingSecurity.class); var request=new MockHttpServletRequest();
        when(security.requireIdentity(request)).thenReturn(new com.aetheris.serving.security.ServingIdentity(7L,"alice"));
        new AnalyticsController(mock(AnalyticsService.class),mock(AnalyticsRepository.class),security).publishDataset(8L,request);
        verify(security).require(request,"dataset",8L,"publish");
        verify(security,never()).requireManage(request);
    }
}
