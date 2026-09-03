package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.DataSourceColumnDto;
import com.aetheris.metadata.dto.MetricQueryRequest;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.MetadataTable;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.entity.SemanticDimension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticMetricQueryServiceTest {
    private final MetadataTableService tableService = mock(MetadataTableService.class);
    private final DataSourceService dataSourceService = mock(DataSourceService.class);
    private final SemanticDimensionService dimensionService = mock(SemanticDimensionService.class);
    private final LogicalModelService logicalModelService = mock(LogicalModelService.class);
    private final BusinessDomainService domainService = mock(BusinessDomainService.class);
    private final JdbcService jdbcService = mock(JdbcService.class);
    private final SemanticMetricQueryService service = new SemanticMetricQueryService(
            tableService, dataSourceService, dimensionService, logicalModelService,
            domainService, jdbcService, new ObjectMapper());

    private final MetadataTable table = new MetadataTable();
    private final DataSource source = new DataSource();
    private final SemanticDimension region = new SemanticDimension();

    @BeforeEach
    void setUp() {
        table.setId(10L);
        table.setDatasourceId(3L);
        table.setSchemaName("sales");
        table.setTableName("orders");
        table.setStatus("active");
        source.setId(3L);
        source.setType("postgresql");
        region.setId(7L);
        region.setDimensionCode("REGION");
        region.setDimensionName("区域");
        region.setMetadataTableId(10L);
        region.setColumnName("region_name");
        region.setStatus("published");
        region.setVersion(1);
        region.setPublishedVersion(1);

        when(tableService.getById(10L)).thenReturn(table);
        when(dataSourceService.getById(3L)).thenReturn(source);
        when(dimensionService.requirePublished(7L)).thenReturn(region);
        when(jdbcService.listColumns(source, "sales", "orders")).thenReturn(List.of(
                column("region_name", "varchar"), column("pay_amount", "numeric"),
                column("paid_at", "timestamp"), column("status", "varchar")));
    }

    @Test
    void compilesControlledAggregateWithPublishedDimensionsAndDateRange() {
        MetricDefinition metric = metric();
        when(jdbcService.query(any(DataSource.class), anyString())).thenReturn(Map.of(
                "columns", List.of("region", "time_bucket", "metric_value"), "rows", List.of()));

        Map<String, Object> result = service.query(metric,
                new MetricQueryRequest(List.of(), "day", "2026-08-01", "2026-08-31", 500));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcService).query(any(DataSource.class), sql.capture());
        assertTrue(sql.getValue().contains("SUM(\"pay_amount\") AS \"metric_value\""));
        assertTrue(sql.getValue().contains("DATE_TRUNC('day', \"paid_at\")"));
        assertTrue(sql.getValue().contains("\"status\" = 'PAID'"));
        assertTrue(sql.getValue().contains("\"paid_at\" < '2026-09-01'"));
        assertTrue(sql.getValue().endsWith("LIMIT 100"), "服务端必须强制限制结果集大小");
        assertEquals(List.of("REGION"), result.get("dimensions"));
    }

    @Test
    void rejectsFilterColumnsOutsidePhysicalSchema() {
        MetricDefinition metric = metric();
        metric.setFiltersJson("[{\"column\":\"not_a_column\",\"operator\":\"eq\",\"value\":1}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validate(metric));

        assertTrue(error.getMessage().contains("在来源表中不存在"));
        verify(jdbcService, never()).query(any(), anyString());
    }

    @Test
    void rejectsDimensionBoundToAnotherTable() {
        region.setMetadataTableId(99L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.validate(metric()));

        assertTrue(error.getMessage().contains("维度与指标来源表不一致"));
    }

    private MetricDefinition metric() {
        MetricDefinition metric = new MetricDefinition();
        metric.setMetricCode("GMV");
        metric.setMetricName("成交金额");
        metric.setSourceTableId(10L);
        metric.setAggregation("SUM");
        metric.setMeasureColumn("pay_amount");
        metric.setFiltersJson("[{\"column\":\"status\",\"operator\":\"eq\",\"value\":\"PAID\"}]");
        metric.setTimeColumn("paid_at");
        metric.setDefaultTimeGrain("day");
        metric.setDimensionIdsJson("[7]");
        metric.setVersion(1);
        return metric;
    }

    private DataSourceColumnDto column(String name, String type) {
        return new DataSourceColumnDto(name, type, null, true, 1, null);
    }
}
