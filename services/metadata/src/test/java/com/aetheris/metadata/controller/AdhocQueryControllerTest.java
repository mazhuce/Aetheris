package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.AdhocQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdhocQueryControllerTest {

    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final JdbcService jdbcService = mock(JdbcService.class);
    private final AdhocQueryController controller = new AdhocQueryController(dataSourceService, jdbcService);

    @Test
    void delegatesToTheSharedReadOnlyQueryPath() {
        DataSource dataSource = new DataSource();
        dataSource.setId(7L);
        Map<String, Object> queryResult = new LinkedHashMap<>();
        queryResult.put("columns", List.of("count"));
        queryResult.put("rows", List.of(List.of(3)));
        when(dataSourceService.getById(7L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "SELECT COUNT(*) FROM orders")).thenReturn(queryResult);

        var response = controller.query(new AdhocQueryRequest(7L, "SELECT COUNT(*) FROM orders"), "viewer");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(jdbcService).query(dataSource, "SELECT COUNT(*) FROM orders");
    }

    @Test
    void rejectsUnknownDatasource() {
        var response = controller.query(new AdhocQueryRequest(99L, "SELECT 1"), "viewer");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void returnsGuardRejectionAsBadRequest() {
        DataSource dataSource = new DataSource();
        when(dataSourceService.getById(7L)).thenReturn(dataSource);
        when(jdbcService.query(dataSource, "DELETE FROM orders"))
                .thenThrow(new IllegalArgumentException("仅支持只读查询"));

        var response = controller.query(new AdhocQueryRequest(7L, "DELETE FROM orders"), "viewer");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("仅支持只读查询", ((Map<?, ?>) response.getBody()).get("message"));
    }
}
