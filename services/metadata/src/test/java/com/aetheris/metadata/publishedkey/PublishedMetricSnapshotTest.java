package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.controller.InternalSemanticMetricController;
import com.aetheris.metadata.entity.*;
import com.aetheris.metadata.security.*;
import com.aetheris.metadata.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PublishedMetricSnapshotTest {
    @Test void aggregateWithAnOnlineVersionCannotExecuteItsNewDraftAsThatVersion(){
        var executor=mock(SemanticMetricQueryService.class);
        var service=new MetricDefinitionService(mock(com.aetheris.metadata.mapper.MetricDefinitionMapper.class),mock(BusinessDomainService.class),
                mock(LogicalModelService.class),executor,mock(org.springframework.jdbc.core.JdbcTemplate.class),new com.fasterxml.jackson.databind.ObjectMapper());
        var snapshot=new MetricDefinition();snapshot.setId(17L);snapshot.setStatus("draft");snapshot.setVersion(2);snapshot.setPublishedVersion(1);
        assertThrows(IllegalArgumentException.class,()->service.queryPublished(snapshot,null));snapshot.setStatus("published");
        assertThrows(IllegalArgumentException.class,()->service.queryPublished(snapshot,null));verifyNoInteractions(executor);
        snapshot.setVersion(1);when(executor.query(snapshot,null)).thenReturn(Map.of("rowCount",1));assertEquals(1,service.queryPublished(snapshot,null).get("rowCount"));
    }
    @Test void ordinaryUserQueryExecutesTheAuthorizedSnapshotAndAuditsDatasourceNotSourceTable(){
        var metrics=mock(MetricDefinitionService.class);var security=mock(MetadataSecurity.class);var audit=mock(QueryAuditService.class);var tables=mock(MetadataTableService.class);var request=mock(HttpServletRequest.class);
        var controller=new InternalSemanticMetricController(metrics,security,audit);controller.setSourceTables(tables);
        var snapshot=new MetricDefinition();snapshot.setId(17L);snapshot.setMetricCode("orders_total");snapshot.setSourceTableId(13L);snapshot.setBusinessDomainId(9L);
        var table=new MetadataTable();table.setId(13L);table.setDatasourceId(5L);when(tables.getById(13L)).thenReturn(table);when(metrics.requirePublished("orders_total")).thenReturn(snapshot);
        when(security.require(request,"metric",17L,"query",Map.of("domainId","9"))).thenReturn(new AetherisIdentity(7L,"owner"));
        var result=Map.<String,Object>of("rowCount",1,"generatedSql","SELECT SUM(amount) FROM business.orders");when(metrics.queryPublished(snapshot,null)).thenReturn(result);
        assertSame(result,controller.query(request,"orders_total",null));var order=inOrder(security,metrics);order.verify(metrics).requirePublished("orders_total");order.verify(security).require(request,"metric",17L,"query",Map.of("domainId","9"));order.verify(metrics).queryPublished(snapshot,null);verify(metrics,never()).query(anyString(),any());
        verify(audit).record(eq("metric"),eq("orders_total"),eq(17L),eq(5L),eq("owner"),eq("success"),eq(1L),anyLong(),eq(false),isNull(),isNull(),eq("SELECT SUM(amount) FROM business.orders"));
    }
}
