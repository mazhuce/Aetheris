package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.DataSourceSchemaDto;
import com.aetheris.metadata.dto.DataSourceTableDto;
import com.aetheris.metadata.dto.MetadataSyncResult;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetadataSyncServiceTest {

    @Test
    void registersDiscoveredTables() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listSchemas(any())).thenReturn(List.of(new DataSourceSchemaDto("public")));
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("orders", "TABLE", "订单表")));
        when(jdbcService.listColumns(any(), anyString(), anyString()))
                .thenReturn(List.of(new DataSourceColumnDto("id", "bigint", 8, false, 1, null)));
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());

        MetadataSyncResult result =
                new MetadataSyncService(jdbcService, tableService).sync(dataSource(), null);

        ArgumentCaptor<MetadataTable> captor = ArgumentCaptor.forClass(MetadataTable.class);
        verify(tableService).save(captor.capture());
        assertEquals("orders", captor.getValue().getTableName());
        assertEquals("public", captor.getValue().getSchemaName());
        assertEquals("订单表", captor.getValue().getTableComment());
        assertEquals("auto", captor.getValue().getSource());
        assertEquals(1, captor.getValue().getColumnCount());
        assertEquals(1, result.created());
        assertEquals(1, result.scanned());
    }

    @Test
    void preservesManuallyRegisteredTables() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("orders", "TABLE", "库中注释")));
        MetadataTable manual = existing("public", "orders", "manual");
        manual.setTableComment("人工维护的说明");
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(manual));

        MetadataSyncResult result =
                new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "public");

        verify(tableService, never()).save(any(MetadataTable.class));
        verify(tableService, never()).updateById(any());
        assertEquals(1, result.skipped());
        assertEquals("人工维护的说明", manual.getTableComment());
    }

    @Test
    void marksVanishedAutoTablesAsStale() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString())).thenReturn(List.of());
        MetadataTable dropped = existing("public", "legacy_orders", "auto");
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(dropped));

        MetadataSyncResult result =
                new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "public");

        assertEquals("stale", dropped.getStatus());
        assertEquals(1, result.removed());
    }

    @Test
    void keepsExistingCommentWhenSourceHasNone() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("orders", "TABLE", null)));
        when(jdbcService.listColumns(any(), anyString(), anyString())).thenReturn(List.of());
        MetadataTable current = existing("public", "orders", "auto");
        current.setTableComment("已补充的说明");
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(current));

        MetadataSyncResult result =
                new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "public");

        assertEquals("已补充的说明", current.getTableComment());
        assertEquals(1, result.updated());
    }

    @Test
    void skipsStaleMarkingWhenSchemaScanFailed() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString()))
                .thenThrow(new IllegalStateException("连接超时"));
        MetadataTable current = existing("public", "orders", "auto");
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(current));

        MetadataSyncResult result =
                new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "public");

        // 采集失败时不能把表误判为已删除。
        assertEquals(0, result.removed());
        assertEquals("active", current.getStatus());
        assertEquals(1, result.failures().size());
    }

    @Test
    void toleratesColumnCountFailure() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("orders", "TABLE", null)));
        when(jdbcService.listColumns(any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("权限不足"));
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());

        MetadataSyncResult result =
                new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "public");

        ArgumentCaptor<MetadataTable> captor = ArgumentCaptor.forClass(MetadataTable.class);
        verify(tableService).save(captor.capture());
        assertNull(captor.getValue().getColumnCount());
        assertEquals(1, result.created());
    }

    @Test
    void assignsWarehouseLayerOnDiscovery() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("dwd_order", "TABLE", "订单明细")));
        when(jdbcService.listColumns(any(), anyString(), anyString())).thenReturn(List.of());
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());

        new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "dwd");

        ArgumentCaptor<MetadataTable> captor = ArgumentCaptor.forClass(MetadataTable.class);
        verify(tableService).save(captor.capture());
        assertEquals("dwd", captor.getValue().getLayer());
        assertEquals("auto", captor.getValue().getLayerSource());
    }

    @Test
    void preservesManuallyAssignedLayer() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        // 库名会推断出 ods，但该记录的层级已被人工改成 dim，采集不应覆盖。
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("ods_calendar", "TABLE", null)));
        when(jdbcService.listColumns(any(), anyString(), anyString())).thenReturn(List.of());
        MetadataTable current = existing("ods", "ods_calendar", "auto");
        current.setLayer("dim");
        current.setLayerSource("manual");
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(current));

        new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "ods");

        assertEquals("dim", current.getLayer());
        assertEquals("manual", current.getLayerSource());
    }

    @Test
    void refreshesAutoAssignedLayerOnResync() {
        JdbcService jdbcService = mock(JdbcService.class);
        MetadataTableService tableService = mock(MetadataTableService.class);
        when(jdbcService.listTables(any(), anyString()))
                .thenReturn(List.of(new DataSourceTableDto("ads_order_stats", "TABLE", null)));
        when(jdbcService.listColumns(any(), anyString(), anyString())).thenReturn(List.of());
        // 此前推断为 other 的记录，重新采集后应更新为正确层级。
        MetadataTable current = existing("ads", "ads_order_stats", "auto");
        current.setLayer("other");
        current.setLayerSource("auto");
        when(tableService.list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(current));

        new MetadataSyncService(jdbcService, tableService).sync(dataSource(), "ads");

        assertEquals("ads", current.getLayer());
    }

    private DataSource dataSource() {
        DataSource dataSource = new DataSource();
        dataSource.setId(1L);
        dataSource.setName("Aetheris PostgreSQL");
        dataSource.setType("postgresql");
        return dataSource;
    }

    private MetadataTable existing(String schema, String table, String source) {
        MetadataTable record = new MetadataTable();
        record.setId(10L);
        record.setDatasourceName("Aetheris PostgreSQL");
        record.setSchemaName(schema);
        record.setTableName(table);
        record.setSource(source);
        record.setStatus("active");
        return record;
    }
}
