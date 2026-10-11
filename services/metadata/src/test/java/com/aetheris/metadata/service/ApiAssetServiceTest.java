package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.ApiAsset;
import com.aetheris.metadata.mapper.ApiAssetMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAssetServiceTest {
    private final ApiAssetMapper mapper = mock(ApiAssetMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ApiAssetService service = new ApiAssetService(mapper, jdbc);

    @Test
    void createAlwaysStartsInRegisteredState() {
        ApiAsset asset = asset();
        asset.setStatus("published");

        service.create(asset);

        assertEquals("registered", asset.getStatus());
        assertEquals("POST", asset.getHttpMethod());
        assertEquals("v1", asset.getVersion());
    }

    @Test
    void publishRejectsAssetWithoutControlledTarget() {
        ApiAsset asset = asset();
        when(mapper.selectById(7L)).thenReturn(asset);

        assertThrows(IllegalArgumentException.class, () -> service.publish(7L, "admin"));
    }

    @Test
    void publishValidatesPublishedMetricAndRecordsOperator() {
        ApiAsset asset = asset();
        asset.setTargetType("metric");
        asset.setTargetRef("GMV_DAILY");
        when(mapper.selectById(7L)).thenReturn(asset);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("GMV_DAILY"))).thenReturn(1);
        when(mapper.updateById(any(ApiAsset.class))).thenReturn(1);

        ApiAsset result = service.publish(7L, "alice");

        assertEquals("published", result.getStatus());
        assertEquals("alice", result.getPublishedBy());
        verify(jdbc).queryForObject(startsWith("SELECT count(*) FROM metadata.metric_definition"), eq(Integer.class), eq("GMV_DAILY"));
    }

    @Test
    void publishValidatesPublishedTableUsingMetadataSchema() {
        ApiAsset asset = asset();
        asset.setTargetType("table");
        asset.setTargetRef("42");
        when(mapper.selectById(7L)).thenReturn(asset);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(42L))).thenReturn(1);
        when(mapper.updateById(any(ApiAsset.class))).thenReturn(1);

        ApiAsset result = service.publish(7L, "alice");

        assertEquals("published", result.getStatus());
        assertEquals("alice", result.getPublishedBy());
        verify(jdbc).queryForObject(startsWith("SELECT count(*) FROM metadata.metadata_table"), eq(Integer.class), eq(42L));
    }

    @Test
    void publishedAssetCannotBeEdited() {
        ApiAsset asset = asset();
        asset.setStatus("published");
        when(mapper.selectById(7L)).thenReturn(asset);

        assertThrows(IllegalStateException.class, () -> service.update(asset));
    }

    @Test
    void publishRejectsNonPostExecutionContract() {
        ApiAsset asset = asset();
        asset.setHttpMethod("GET");
        asset.setTargetType("metric");
        asset.setTargetRef("GMV_DAILY");
        when(mapper.selectById(7L)).thenReturn(asset);

        assertThrows(IllegalArgumentException.class, () -> service.publish(7L, "admin"));
    }

    private ApiAsset asset() {
        ApiAsset asset = new ApiAsset();
        asset.setId(7L);
        asset.setAssetCode("PUBLIC_METRIC");
        asset.setAssetName("Public metric");
        asset.setApiPath("/api/serving/v1/apis/PUBLIC_METRIC/query");
        asset.setStatus("registered");
        return asset;
    }
}
