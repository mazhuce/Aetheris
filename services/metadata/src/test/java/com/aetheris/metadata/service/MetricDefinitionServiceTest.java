package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.SemanticVersion;
import com.aetheris.metadata.entity.MetricDefinition;
import com.aetheris.metadata.mapper.MetricDefinitionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricDefinitionServiceTest {
    private final MetricDefinitionMapper mapper = mock(MetricDefinitionMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricDefinitionService service = new MetricDefinitionService(
            mapper, mock(BusinessDomainService.class), mock(LogicalModelService.class),
            mock(SemanticMetricQueryService.class), jdbc, objectMapper);

    @Test
    void onlineSnapshotRemainsConsumableWhileNextVersionIsDraft() {
        MetricDefinition aggregate = metric("GMV", "draft", 2);
        aggregate.setId(8L);
        aggregate.setPublishedVersion(1);
        aggregate.setMeasureColumn("draft_amount");
        when(mapper.selectOne(any())).thenReturn(aggregate);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(8L), eq(1)))
                .thenReturn(List.of(new SemanticVersion(20L, 1, publishedSnapshot(), "alice", OffsetDateTime.now())));

        MetricDefinition published = service.requirePublished("gmv");

        assertEquals("published", published.getStatus());
        assertEquals(1, published.getVersion());
        assertEquals("pay_amount", published.getMeasureColumn());
        assertEquals(1, published.getPublishedVersion());
    }

    @Test
    void metricCodeCannotChangeAfterCreation() {
        MetricDefinition existing = metric("GMV", "draft", 1);
        existing.setId(8L);
        when(mapper.selectById(8L)).thenReturn(existing);
        MetricDefinition input = metric("REVENUE", "draft", 1);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.update(8L, input));

        assertEquals("指标编码是稳定标识，创建后不能修改", error.getMessage());
    }

    @Test
    void pageAndSizeHaveExplicitSafeBounds() {
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, 0, 50));
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, 1, 501));
    }

    private MetricDefinition metric(String code, String status, int version) {
        MetricDefinition metric = new MetricDefinition();
        metric.setMetricCode(code);
        metric.setMetricName("成交金额");
        metric.setStatus(status);
        metric.setVersion(version);
        metric.setLayer("atomic");
        metric.setAggregation("SUM");
        metric.setMeasureColumn("pay_amount");
        metric.setFiltersJson("[]");
        metric.setDimensionIdsJson("[]");
        metric.setDefaultTimeGrain("none");
        return metric;
    }

    private ObjectNode publishedSnapshot() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("metricCode", "GMV");
        node.put("metricName", "成交金额");
        node.put("businessDomainId", 0);
        node.put("logicalModelId", 0);
        node.put("sourceTableId", 10);
        node.put("layer", "atomic");
        node.put("calibreFormula", "支付金额求和");
        node.put("aggregation", "SUM");
        node.put("measureColumn", "pay_amount");
        node.set("filters", objectMapper.createArrayNode());
        node.put("timeColumn", "paid_at");
        node.put("defaultTimeGrain", "day");
        node.set("dimensionIds", objectMapper.createArrayNode());
        node.put("unit", "元");
        node.put("owner", "alice");
        node.put("description", "线上口径");
        return node;
    }
}
