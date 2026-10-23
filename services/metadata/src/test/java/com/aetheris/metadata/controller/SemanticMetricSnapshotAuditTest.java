package com.aetheris.metadata.controller;

import com.aetheris.metadata.entity.*;
import com.aetheris.metadata.security.*;
import com.aetheris.metadata.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SemanticMetricSnapshotAuditTest {
    private final MetricDefinitionService metrics=mock(MetricDefinitionService.class);
    private final MetadataSecurity security=mock(MetadataSecurity.class);
    private final QueryAuditService audit=mock(QueryAuditService.class);
    private final MetadataTableService tables=mock(MetadataTableService.class);
    private final SemanticMetricController controller=new SemanticMetricController(metrics,security,audit);
    private final MetricDefinition snapshot=new MetricDefinition();

    SemanticMetricSnapshotAuditTest() {
        controller.setSourceTables(tables);
        snapshot.setId(17L);snapshot.setMetricCode("STABLE");snapshot.setSourceTableId(13L);snapshot.setBusinessDomainId(9L);
        var table=new MetadataTable();table.setId(13L);table.setDatasourceId(5L);
        when(tables.getById(13L)).thenReturn(table);
        when(metrics.requirePublished("STABLE")).thenReturn(snapshot);
        when(security.require(any(),eq("metric"),eq(17L),eq("query"),eq(Map.of("domainId","9"))))
                .thenReturn(new AetherisIdentity(7L,"owner"));
    }

    @Test void sameAuthorizedSnapshotExecutesAndAuditFailureDoesNotRewriteSuccessfulResult() {
        var result=Map.<String,Object>of("rowCount",1L,"generatedSql","SELECT COUNT(*) FROM business.orders");
        when(metrics.queryPublished(same(snapshot),isNull())).thenReturn(result);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(anyString(),anyString(),anyLong(),anyLong(),anyString(),anyString(),any(),anyLong(),anyBoolean(),any(),any(),any());
        assertSame(result,controller.query(mock(HttpServletRequest.class),"STABLE",null));
        var order=inOrder(metrics,security);order.verify(metrics).requirePublished("STABLE");
        order.verify(security).require(any(),eq("metric"),eq(17L),eq("query"),eq(Map.of("domainId","9")));
        order.verify(metrics).queryPublished(same(snapshot),isNull());
        verify(metrics,never()).query(anyString(),any());
        verify(audit,times(1)).record(eq("metric"),eq("STABLE"),eq(17L),eq(5L),eq("owner"),eq("success"),eq(1L),anyLong(),eq(false),isNull(),isNull(),eq("SELECT COUNT(*) FROM business.orders"));
    }

    @Test void driverFailureHasFixed502AndOneSanitizedFailureAudit() throws Exception {
        when(metrics.queryPublished(same(snapshot),isNull())).thenThrow(new IllegalStateException("driver SQL/password=must-not-leak"));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        String body=mvc.perform(post("/api/metadata/semantic/metrics/STABLE/query"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("QUERY_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("must-not-leak"));assertFalse(body.contains("password"));
        verify(audit,times(1)).record(eq("metric"),eq("STABLE"),eq(17L),eq(5L),eq("owner"),eq("failed"),isNull(),anyLong(),eq(false),eq("QUERY_FAILED"),isNull(),isNull());
    }

    @Test void invalidQueryIsFixed400WhileAuthorization503IsPreserved() throws Exception {
        when(metrics.queryPublished(same(snapshot),isNull())).thenThrow(new IllegalArgumentException("unsafe-driver-or-input"));
        var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MetadataAccessExceptionHandler()).build();
        mvc.perform(post("/api/metadata/semantic/metrics/STABLE/query")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("指标查询参数无效"));
        when(security.require(any(),eq("metric"),eq(17L),eq("query"),anyMap()))
                .thenThrow(new MetadataSecurity.PermissionServiceUnavailableException("权限服务暂时不可用"));
        mvc.perform(post("/api/metadata/semantic/metrics/STABLE/query")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_UNAVAILABLE"));
        verify(metrics,times(1)).queryPublished(any(),any());
    }
}
