package com.aetheris.serving.service;

import com.aetheris.serving.dto.DashboardCreateRequest;
import com.aetheris.serving.dto.DashboardUpdateRequest;
import com.aetheris.serving.model.AnalyticsDataset;
import com.aetheris.serving.model.Dashboard;
import com.aetheris.serving.model.DashboardTemplate;
import com.aetheris.serving.repository.AnalyticsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {

    private final AnalyticsRepository repository = mock(AnalyticsRepository.class);
    private final MetadataQueryClient queryClient = mock(MetadataQueryClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AnalyticsService service = new AnalyticsService(repository, queryClient, objectMapper);

    private AnalyticsDataset dataset(Long id) {
        return new AnalyticsDataset(id, "订单数据集", null, "sql", 10L, "订单库",
                "SELECT * FROM dwd.orders", null, null, null, 30, "active", "admin", null, null);
    }

    private AnalyticsDataset metricDataset(Long id) {
        return new AnalyticsDataset(id, "成交金额", null, "metric", null, null,
                null, "GMV", objectMapper.createObjectNode().put("timeGrain", "day"),
                null, 30, "active", "admin", null, null);
    }

    private Dashboard dashboard(Long id, String status, int publishedVersion) {
        return new Dashboard(id, "销售看板", null, "dash-abc", status,
                objectMapper.createObjectNode(), null, publishedVersion,
                "admin", "admin", null, null, null);
    }

    @Test
    void datasetThrowsWhenMissing() {
        when(repository.findDataset(99L)).thenReturn(Optional.empty());
        AnalyticsService.NotFoundException ex = assertThrows(
                AnalyticsService.NotFoundException.class, () -> service.dataset(99L));
        assertEquals("数据集不存在", ex.getMessage());
    }

    @Test
    void createDashboardWithoutTemplateBuildsBlankSpec() {
        when(repository.createDashboard(anyString(), any(), anyString(), any(JsonNode.class), eq("alice")))
                .thenReturn(dashboard(1L, "draft", 0));

        Dashboard result = service.createDashboard(new DashboardCreateRequest("销售看板", "描述", null), "alice");

        assertEquals(1L, result.id());
        ArgumentCaptor<JsonNode> specCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).createDashboard(eq("销售看板"), eq("描述"), anyString(), specCaptor.capture(), eq("alice"));
        JsonNode spec = specCaptor.getValue();
        assertEquals(1, spec.get("schemaVersion").asInt());
        assertEquals("dark", spec.get("theme").asText());
        assertTrue(spec.get("layouts").get("lg").isEmpty());
        assertTrue(spec.get("widgets").isEmpty());
        assertTrue(spec.get("globalFilters").isEmpty());
    }

    @Test
    void createDashboardWithTemplateUsesTemplateSpec() {
        JsonNode templateSpec = objectMapper.createObjectNode().put("theme", "light");
        DashboardTemplate template = new DashboardTemplate(5L, "sales", "销售模板", null,
                "电商", "blue", templateSpec, 1);
        when(repository.findTemplate(5L)).thenReturn(Optional.of(template));
        when(repository.createDashboard(anyString(), any(), anyString(), any(JsonNode.class), anyString()))
                .thenReturn(dashboard(2L, "draft", 0));

        service.createDashboard(new DashboardCreateRequest("看板", null, 5L), "admin");

        ArgumentCaptor<JsonNode> specCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository).createDashboard(eq("看板"), any(), anyString(), specCaptor.capture(), anyString());
        assertEquals("light", specCaptor.getValue().get("theme").asText());
    }

    @Test
    void createDashboardWithUnknownTemplateThrows() {
        when(repository.findTemplate(99L)).thenReturn(Optional.empty());
        AnalyticsService.NotFoundException ex = assertThrows(
                AnalyticsService.NotFoundException.class,
                () -> service.createDashboard(new DashboardCreateRequest("看板", null, 99L), "admin"));
        assertEquals("模板不存在", ex.getMessage());
    }

    @Test
    void updateDashboardThrowsWhenMissing() {
        when(repository.updateDashboard(anyLong(), anyString(), any(), any(), anyString()))
                .thenReturn(Optional.empty());
        assertThrows(AnalyticsService.NotFoundException.class,
                () -> service.updateDashboard(99L, new DashboardUpdateRequest("x", null, objectMapper.createObjectNode()), "admin"));
    }

    @Test
    void deleteDashboardThrowsWhenNotRemoved() {
        when(repository.deleteDashboard(99L)).thenReturn(false);
        assertThrows(AnalyticsService.NotFoundException.class, () -> service.deleteDashboard(99L));
    }

    @Test
    void publishThrowsWhenDashboardMissing() {
        when(repository.publishDashboard(99L, "admin")).thenReturn(Optional.empty());
        assertThrows(AnalyticsService.NotFoundException.class, () -> service.publish(99L, "admin"));
    }

    @Test
    void publishMapsRepositoryRaceToConflict() {
        when(repository.publishDashboard(1L, "admin"))
                .thenThrow(new AnalyticsRepository.PublishConflictException("看板已发布，请先修改草稿后再发布"));

        AnalyticsService.PublishConflictException conflict = assertThrows(
                AnalyticsService.PublishConflictException.class, () -> service.publish(1L, "admin"));
        assertEquals("看板已发布，请先修改草稿后再发布", conflict.getMessage());
    }

    @Test
    void publishedThrowsWhenNotPublished() {
        when(repository.findPublishedDashboard("nope")).thenReturn(Optional.empty());
        assertThrows(AnalyticsService.NotFoundException.class, () -> service.published("nope"));
    }

    @Test
    void queryDatasetCachesResultAndUpdatesSchema() {
        when(repository.findDataset(1L)).thenReturn(Optional.of(dataset(1L)));
        when(queryClient.query(10L, "SELECT * FROM dwd.orders"))
                .thenReturn(Map.of("columns", Map.of("orderNo", "string")));

        Map<String, Object> first = service.queryDataset(1L, false);
        Map<String, Object> second = service.queryDataset(1L, false);

        assertEquals(1, queryInvocations());
        assertEquals(Map.of("columns", Map.of("orderNo", "string")), first);
        assertEquals(first, second);
        ArgumentCaptor<JsonNode> schemaCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(repository, times(1)).updateDatasetSchema(eq(1L), schemaCaptor.capture());
        assertEquals("string", schemaCaptor.getValue().get("columns").get("orderNo").asText());
    }

    @Test
    void queryDatasetWithRefreshRequeries() {
        when(repository.findDataset(1L)).thenReturn(Optional.of(dataset(1L)));
        when(queryClient.query(anyLong(), anyString()))
                .thenReturn(Map.of("columns", java.util.List.of()));

        service.queryDataset(1L, false);
        service.queryDataset(1L, true);

        verify(queryClient, times(2)).query(10L, "SELECT * FROM dwd.orders");
        verify(repository, times(2)).updateDatasetSchema(eq(1L), any(JsonNode.class));
    }

    @Test
    void semanticDatasetUsesPublishedMetricEndpoint() {
        when(repository.findDataset(2L)).thenReturn(Optional.of(metricDataset(2L)));
        when(queryClient.queryMetric(eq("GMV"), any())).thenReturn(Map.of(
                "columns", java.util.List.of("time_bucket", "metric_value"),
                "rows", java.util.List.of()));

        service.queryDataset(2L, true);

        verify(queryClient).queryMetric(eq("GMV"), any());
        verify(queryClient, never()).query(anyLong(), anyString());
    }

    @Test
    void queryDatasetThrowsWhenDatasetMissing() {
        when(repository.findDataset(99L)).thenReturn(Optional.empty());
        assertThrows(AnalyticsService.NotFoundException.class, () -> service.queryDataset(99L, false));
        verify(queryClient, never()).query(anyLong(), anyString());
    }

    /** 使用 mockingDetails 获取调用次数，避免 verify(atLeast(0)) 反模式。 */
    private long queryInvocations() {
        return mockingDetails(queryClient).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("query"))
                .count();
    }
}
