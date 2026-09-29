package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.DataSourceHealthService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.QueryHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSourceControllerTest {

    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final JdbcService jdbcService = mock(JdbcService.class);
    private final QueryHistoryService queryHistoryService = mock(QueryHistoryService.class);
    private final MetadataSecurity security = mock(MetadataSecurity.class);
    private final DataSourceHealthService healthService = mock(DataSourceHealthService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final DataSourceController controller = new DataSourceController(
            dataSourceService, jdbcService, queryHistoryService, security, healthService);

    @Test
    void successfulDirectQueryRecordsHistoryAndDuration() {
        DataSource dataSource = dataSource(5L, "Spark warehouse");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", List.of("count"));
        result.put("rows", List.of(List.of(3)));
        result.put("rowCount", 1);
        allowQuery(5L, "admin");
        when(dataSourceService.getById(5L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "SELECT COUNT(*) FROM ads.ads_order_stats"))
                .thenReturn(result);

        var response = controller.query(request, 5L,
                Map.of("sql", "SELECT COUNT(*) FROM ads.ads_order_stats"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody().get("durationMs"));
        verify(queryHistoryService).record(argThat(history ->
                "admin".equals(history.getUsername())
                        && Long.valueOf(5L).equals(history.getDatasourceId())
                        && "success".equals(history.getStatus())
                        && Long.valueOf(1L).equals(history.getRowCount())
                        && history.getErrorMessage() == null));
    }

    @Test
    void rejectedDirectQueryRecordsSafeFailure() {
        DataSource dataSource = dataSource(5L, "Spark warehouse");
        allowQuery(5L, "admin");
        when(dataSourceService.getById(5L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "DROP TABLE ads.ads_order_stats"))
                .thenThrow(new IllegalArgumentException("查询中包含不允许的关键字：DROP"));

        var response = controller.query(request, 5L,
                Map.of("sql", "DROP TABLE ads.ads_order_stats"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("查询中包含不允许的关键字：DROP", response.getBody().get("message"));
        verify(queryHistoryService).record(argThat(history ->
                "failed".equals(history.getStatus())
                        && "查询中包含不允许的关键字：DROP".equals(history.getErrorMessage())));
    }

    @Test
    void jdbcFailureReturnsSafeMessageAndRecordsFailure() {
        DataSource dataSource = dataSource(5L, "Spark warehouse");
        allowQuery(5L, "admin");
        when(dataSourceService.getById(5L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "SELECT 1"))
                .thenThrow(new IllegalStateException("目标端口拒绝连接，请确认数据库服务已启动并监听外部地址"));

        var response = controller.query(request, 5L, Map.of("sql", "SELECT 1"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("目标端口拒绝连接，请确认数据库服务已启动并监听外部地址",
                response.getBody().get("message"));
                verify(queryHistoryService).record(argThat(history ->
                "failed".equals(history.getStatus())
                        && history.getDurationMs() != null
                        && history.getErrorMessage() != null));
    }

    @Test
    void exposesBackendDataSourceCatalog() {
        when(security.require(request, "datasource", null, "view", Map.of()))
                .thenReturn(new AetherisIdentity(1L, "admin"));

        var definitions = controller.catalog(request);

        assertEquals(21, definitions.size());
        assertEquals("postgresql", definitions.get(0).type());
        assertEquals("trino", definitions.get(definitions.size() - 1).type());
    }

    private void allowQuery(Long datasourceId, String username) {
        when(security.require(request, "datasource", datasourceId, "query", Map.of()))
                .thenReturn(new AetherisIdentity(1L, username));
    }

    private DataSource dataSource(Long id, String name) {
        DataSource dataSource = new DataSource();
        dataSource.setId(id);
        dataSource.setName(name);
        return dataSource;
    }
}
