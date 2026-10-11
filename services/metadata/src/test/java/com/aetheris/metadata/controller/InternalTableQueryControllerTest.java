package com.aetheris.metadata.controller;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.security.AetherisIdentity;
import com.aetheris.metadata.security.MetadataSecurity;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import com.aetheris.metadata.service.MetadataTableService;
import com.aetheris.metadata.service.QueryAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalTableQueryControllerTest {

    private final MetadataTableService tables = mock(MetadataTableService.class);
    private final DataSourceService dataSources = mock(DataSourceService.class);
    private final JdbcService jdbc = mock(JdbcService.class);
    private final MetadataSecurity security = mock(MetadataSecurity.class);
    private final QueryAuditService audit = mock(QueryAuditService.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final InternalTableQueryController controller =
            new InternalTableQueryController(tables, dataSources, jdbc, security, audit);

    @Test
    void tableQueryRequiresPermissionAndRecordsAudit() {
        MetadataTable table = new MetadataTable();
        table.setId(42L);
        table.setDatasourceId(5L);
        table.setSchemaName("public");
        table.setTableName("orders");
        table.setStatus("active");
        table.setBusinessDomainId(9L);
        DataSource source = new DataSource();
        source.setId(5L);
        source.setType("postgresql");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rowCount", 2);

        when(tables.getById(42L)).thenReturn(table);
        when(dataSources.getById(5L)).thenReturn(source);
        when(security.require(request, "table", 42L, "query", Map.of("domainId", "9")))
                .thenReturn(new AetherisIdentity(7L, "alice"));
        when(jdbc.listColumns(source, "public", "orders")).thenReturn(List.of(
                new DataSourceColumnDto("id", "bigint", null, false, 1, null),
                new DataSourceColumnDto("amount", "numeric", null, true, 2, null)));
        when(jdbc.query(eq(source), anyString())).thenReturn(result);

        var response = controller.query(request, 42L,
                new com.aetheris.metadata.dto.TableQueryRequest(List.of("id"), 10));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(2, response.getBody().get("rowCount"));
        verify(security).require(request, "table", 42L, "query", Map.of("domainId", "9"));
        verify(audit).record(eq("table"), eq(null), eq(42L), eq(5L), eq("alice"), eq("success"),
                eq(2L), argThat(value -> value != null && value >= 0), eq(false), eq(null), eq(null),
                argThat(sql -> sql.contains("SELECT \"id\" FROM \"public\".\"orders\" LIMIT 10")));
    }
}
