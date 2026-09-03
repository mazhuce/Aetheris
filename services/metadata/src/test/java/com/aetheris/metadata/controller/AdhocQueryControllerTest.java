package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.AdhocQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.QueryHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdhocQueryControllerTest {

    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final JdbcService jdbcService = mock(JdbcService.class);
    private final QueryHistoryService queryHistoryService = mock(QueryHistoryService.class);
    private final MetadataSecurity security = mock(MetadataSecurity.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final AdhocQueryController controller =
            new AdhocQueryController(dataSourceService, jdbcService, queryHistoryService, security);

    /** 模拟 auth 决策引擎放行：身份来自网关验签，用户名由判定结果返回。 */
    private void allowQuery(Long datasourceId, String username) {
        when(security.require(request, "datasource", datasourceId, "query", Map.of()))
                .thenReturn(new AetherisIdentity(1L, username));
    }

    @Test
    void delegatesToTheSharedReadOnlyQueryPath() {
        DataSource dataSource = new DataSource();
        dataSource.setId(7L);
        Map<String, Object> queryResult = new LinkedHashMap<>();
        queryResult.put("columns", List.of("count"));
        queryResult.put("rows", List.of(List.of(3)));
        allowQuery(7L, "viewer");
        when(dataSourceService.getById(7L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "SELECT COUNT(*) FROM orders")).thenReturn(queryResult);

        var response = controller.query(request, new AdhocQueryRequest(7L, "SELECT COUNT(*) FROM orders"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jdbcService).query(dataSource, "SELECT COUNT(*) FROM orders");
    }

    @Test
    void rejectsUnknownDatasource() {
        allowQuery(99L, "viewer");
        var response = controller.query(request, new AdhocQueryRequest(99L, "SELECT 1"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void returnsGuardRejectionAsBadRequest() {
        DataSource dataSource = new DataSource();
        allowQuery(7L, "viewer");
        when(dataSourceService.getById(7L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "DELETE FROM orders"))
                .thenThrow(new IllegalArgumentException("仅支持只读查询"));

        var response = controller.query(request, new AdhocQueryRequest(7L, "DELETE FROM orders"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("仅支持只读查询", ((Map<?, ?>) response.getBody()).get("message"));
    }

    @Test
    void deniedPermissionPropagatesForbiddenWithoutTouchingJdbc() {
        when(security.require(request, "datasource", 7L, "query", Map.of()))
                .thenThrow(new MetadataSecurity.ForbiddenException("没有 datasource.query 权限"));

        assertThrows(MetadataSecurity.ForbiddenException.class,
                () -> controller.query(request, new AdhocQueryRequest(7L, "SELECT 1")));

        verify(jdbcService, never()).query(any(), any());
        verify(queryHistoryService, never()).record(any());
    }

    @Test
    void successfulQueryIsRecordedInHistory() {
        DataSource dataSource = new DataSource();
        dataSource.setId(7L);
        Map<String, Object> queryResult = new LinkedHashMap<>();
        queryResult.put("rows", List.of());
        allowQuery(7L, "alice");
        when(dataSourceService.getById(7L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "SELECT 1")).thenReturn(queryResult);

        controller.query(request, new AdhocQueryRequest(7L, "SELECT 1"));

        verify(queryHistoryService).record(org.mockito.ArgumentMatchers.argThat(history ->
                "alice".equals(history.getUsername())
                        && Long.valueOf(7L).equals(history.getDatasourceId())
                        && "success".equals(history.getStatus())
                        && history.getErrorMessage() == null));
    }

    @Test
    void failedQueryIsRecordedInHistory() {
        DataSource dataSource = new DataSource();
        allowQuery(7L, "alice");
        when(dataSourceService.getById(7L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "DELETE FROM orders"))
                .thenThrow(new IllegalArgumentException("仅支持只读查询"));

        controller.query(request, new AdhocQueryRequest(7L, "DELETE FROM orders"));

        verify(queryHistoryService).record(org.mockito.ArgumentMatchers.argThat(history ->
                "failed".equals(history.getStatus())
                        && "仅支持只读查询".equals(history.getErrorMessage())));
    }

    @Test
    void unknownDatasourceRecordsNothing() {
        allowQuery(99L, "alice");
        controller.query(request, new AdhocQueryRequest(99L, "SELECT 1"));

        verify(queryHistoryService, never()).record(any());
    }
}