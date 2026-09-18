package com.aetheris.serving.controller;

import com.aetheris.serving.dto.DashboardCreateRequest;
import com.aetheris.serving.dto.DashboardUpdateRequest;
import com.aetheris.serving.dto.DatasetInput;
import com.aetheris.serving.model.AnalyticsDataset;
import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.model.DashboardTemplate;
import com.aetheris.serving.repository.AnalyticsRepository;
import com.aetheris.serving.service.AnalyticsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {

    private final AnalyticsService service = mock(AnalyticsService.class);
    private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
    private final AnalyticsController controller = new AnalyticsController(service, repository);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ADMIN = "admin";
    private static final String VIEWER = "viewer";

    @Test
    void listDatasetsDelegatesToService() {
        when(service.datasets()).thenReturn(List.of());
        assertEquals(0, controller.datasets().size());
    }

    @Test
    void datasetByIdDelegatesToService() {
        when(service.dataset(1L)).thenReturn(new AnalyticsDataset(
                1L, "ds", null, "sql", 10L, "dsrc", "SELECT 1", null, null,
                null, 30, "active", "admin", null, null));
        assertEquals("ds", controller.dataset(1L).name());
    }

    @Test
    void createDatasetRequiresAdminRole() {
        DatasetInput input = sqlInput("test");
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.createDataset(input, "system", VIEWER));
    }

    @Test
    void createDatasetSucceedsForAdmin() {
        DatasetInput input = sqlInput("test");
        when(service.createDataset(any(), anyString())).thenReturn(new AnalyticsDataset(
                1L, "test", null, "sql", 10L, "test", "SELECT 1", null, null,
                null, 30, "active", "admin", null, null));
        assertEquals("test", controller.createDataset(input, "admin", ADMIN).name());
    }

    @Test
    void updateDatasetRequiresAdminRole() {
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.updateDataset(1L, sqlInput("x"), VIEWER));
    }

    @Test
    void deleteDatasetRequiresAdminRole() {
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.deleteDataset(1L, VIEWER));
    }

    @Test
    void deleteDatasetReturns204() {
        ResponseEntity<Void> response = controller.deleteDataset(1L, ADMIN);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteDataset(1L);
    }

    @Test
    void queryDatasetDelegatesToService() {
        when(service.queryDataset(1L, false)).thenReturn(Map.of("rows", List.of()));
        assertEquals(Map.of("rows", List.of()), controller.queryDataset(1L, false));
    }

    private DatasetInput sqlInput(String name) {
        return new DatasetInput(name, null, "sql", 10L, "test", "SELECT 1", null, null, 30);
    }

    @Test
    void listTemplatesDelegatesToRepository() {
        when(repository.listTemplates()).thenReturn(List.of(new DashboardTemplate(
                1L, "sales", "销售模板", null, null, null, null, 1)));
        assertEquals(1, controller.templates().size());
    }

    @Test
    void listDashboardsDelegatesToRepository() {
        when(repository.listDashboards()).thenReturn(List.of());
        assertEquals(0, controller.dashboards().size());
    }

    @Test
    void publishedDashboardDelegatesToService() {
        when(service.published("dash-abc")).thenReturn(new Dashboard(
                1L, "看板", null, "dash-abc", "published",
                null, null, 1, "admin", "admin", null, null, null));
        assertEquals("看板", controller.published("dash-abc").name());
    }

    @Test
    void createDashboardRequiresAdminRole() {
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.createDashboard(new DashboardCreateRequest("看板", null, null), "system", VIEWER));
    }

    @Test
    void createDashboardSucceedsForAdmin() {
        when(service.createDashboard(any(), anyString())).thenReturn(new Dashboard(
                1L, "看板", null, "dash-abc", "draft",
                null, null, 0, "admin", "admin", null, null, null));
        assertEquals("看板", controller.createDashboard(
                new DashboardCreateRequest("看板", null, null), "admin", ADMIN).name());
    }

    @Test
    void updateDashboardRequiresAdminRole() {
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.updateDashboard(1L,
                        new DashboardUpdateRequest("x", null, objectMapper.createObjectNode()),
                        "admin", VIEWER));
    }

    @Test
    void publishRequiresAdminRole() {
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.publish(1L, "admin", VIEWER));
    }

    @Test
    void deleteDashboardRequiresAdminRole() {
        assertThrows(AnalyticsController.ForbiddenException.class,
                () -> controller.deleteDashboard(1L, VIEWER));
    }

    @Test
    void deleteDashboardReturns204() {
        ResponseEntity<Void> response = controller.deleteDashboard(1L, ADMIN);
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).deleteDashboard(1L);
    }

    @Test
    void notFoundExceptionHandlerReturns404() {
        ResponseEntity<Map<String, String>> response = controller.notFound(
                new AnalyticsService.NotFoundException("数据集不存在"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("数据集不存在", response.getBody().get("message"));
    }

    @Test
    void publishConflictHandlerReturns409() {
        ResponseEntity<Map<String, String>> response = controller.conflict(
                new AnalyticsService.PublishConflictException("看板已发布，请先修改草稿后再发布"));
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("看板已发布，请先修改草稿后再发布", response.getBody().get("message"));
    }

    @Test
    void forbiddenExceptionHandlerReturns403() {
        ResponseEntity<Map<String, String>> response = controller.forbidden();
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody().get("message").contains("管理员"));
    }

    @Test
    void badRequestExceptionHandlerReturns400() {
        ResponseEntity<Map<String, String>> response = controller.badRequest(
                new IllegalArgumentException("参数无效"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("参数无效", response.getBody().get("message"));
    }
}
