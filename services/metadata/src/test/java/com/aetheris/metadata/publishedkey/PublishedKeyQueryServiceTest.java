package com.aetheris.metadata.publishedkey;

import com.aetheris.metadata.dto.*;
import com.aetheris.metadata.entity.*;
import com.aetheris.metadata.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.aetheris.metadata.publishedkey.PublishedKeyContract.*;

class PublishedKeyQueryServiceTest {
    final ApiAssetService assets=mock(ApiAssetService.class);final MetadataTableService tables=mock(MetadataTableService.class);final MetricDefinitionService metrics=mock(MetricDefinitionService.class);
    final BusinessDomainService domains=mock(BusinessDomainService.class);final TableDataQueryService queries=mock(TableDataQueryService.class);final PublishedKeyPeers peers=mock(PublishedKeyPeers.class);final QueryAuditService audit=mock(QueryAuditService.class);
    final AtomicLong millis=new AtomicLong(1792281601000L);final Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(millis.get());}};
    final PublishedKeyQueryService service=new PublishedKeyQueryService(assets,tables,metrics,domains,queries,peers,audit,clock);
    final Reference ref=new Reference("7feb99a1-1801-46b1-9bba-d8254406de99","a".repeat(64),"b".repeat(64));
    final Parameters parameters=new Parameters(List.of(),null,null,null,List.of("id"),10);final Map<String,Object> rows=Map.of("rows",List.of(List.of(1)),"rowCount",1);
    final ApiAsset dbAsset=new ApiAsset();final MetadataTable table=new MetadataTable();final DataSource source=new DataSource();Admission input;
    PublishedKeyQueryServiceTest(){
        dbAsset.setId(11L);dbAsset.setAssetCode("orders_api");dbAsset.setVersion("v1");dbAsset.setTargetType("table");dbAsset.setTargetRef("13");dbAsset.setHttpMethod("POST");dbAsset.setStatus("published");
        table.setId(13L);table.setDatasourceId(5L);table.setBusinessDomainId(9L);table.setStatus("active");source.setId(5L);
        var domain=new BusinessDomain();domain.setId(9L);domain.setDomainCode("warehouse");when(domains.get(9L)).thenReturn(domain);
        when(assets.publishedByCode("orders_api")).thenReturn(dbAsset);when(tables.getById(13L)).thenReturn(table);when(queries.prepare(eq(table),any())).thenReturn(new TableDataQueryService.Plan(source,"SELECT id FROM business.orders LIMIT 10"));when(queries.execute(any())).thenReturn(rows);install();
    }
    void install(){input=new Admission(ref.admissionId(),3,new Owner(7,"owner",2),Asset.from(dbAsset),parameters,parameters.sha256(),"key-request-123",1792281600000L,1792281630000L);when(peers.redeem(ref)).thenReturn(input);}
    @Test void actualTableDomainAndOwnerBindingAuthorizeBeforeAnyBusinessJdbc(){
        assertSame(rows,service.query(ref));var order=inOrder(peers,queries);order.verify(peers).redeem(ref);order.verify(peers).authorize(ref,input,new Target("table",13,9L,"warehouse"));order.verify(queries).prepare(eq(table),eq(new TableQueryRequest(List.of("id"),10)));order.verify(queries).execute(any());
        verify(audit).recordPublished(eq("published_api"),eq("orders_api"),eq(11L),eq(5L),eq("owner"),eq("success"),eq(1L),anyLong(),anyString(),eq(3L),eq(ref.admissionId()),eq("key-request-123"),eq(7L));
    }
    @Test void changedPublishedTargetOrPermissionDenialHasZeroBusinessCalls(){
        dbAsset.setTargetRef("99");assertThrows(Rejected.class,()->service.query(ref));verifyNoInteractions(queries);verify(peers,never()).authorize(any(),any(),any());
        dbAsset.setTargetRef("13");doThrow(new Rejected()).when(peers).authorize(any(),any(),any());assertThrows(Rejected.class,()->service.query(ref));verifyNoInteractions(queries);
    }
    @Test void expiryOrDeprecationWhileAuthorizationRunsCannotStartJdbc(){
        doAnswer(call->{millis.set(1792281630001L);return null;}).when(peers).authorize(any(),any(),any());assertThrows(Rejected.class,()->service.query(ref));verifyNoInteractions(queries);
        millis.set(1792281601000L);doAnswer(call->{dbAsset.setStatus("deprecated");return null;}).when(peers).authorize(any(),any(),any());assertThrows(Rejected.class,()->service.query(ref));verifyNoInteractions(queries);
    }
    @Test void aMissingPersistedDomainNeverBecomesAnUnscopedGrant(){when(domains.get(9L)).thenReturn(null);assertThrows(Rejected.class,()->service.query(ref));verifyNoInteractions(queries);verify(peers,never()).authorize(any(),any(),any());}
    @Test void metricUsesExactlyTheAuthorizedPublishedSnapshotAndCorrectDatasource(){
        dbAsset.setTargetType("metric");dbAsset.setTargetRef("orders_total");install();var metric=new MetricDefinition();metric.setId(17L);metric.setMetricCode("orders_total");metric.setSourceTableId(13L);metric.setBusinessDomainId(9L);metric.setPublishedVersion(1);metric.setStatus("published");
        when(metrics.requirePublished("orders_total")).thenReturn(metric);when(metrics.queryPublished(eq(metric),any())).thenReturn(rows);
        assertSame(rows,service.query(ref));verify(peers).authorize(ref,input,new Target("metric",17,9L,"warehouse"));verify(metrics).queryPublished(eq(metric),eq(new MetricQueryRequest(List.of(),null,null,null,10)));verify(metrics,never()).query(anyString(),any());verifyNoInteractions(queries);
        verify(audit).recordPublished(eq("published_api"),eq("orders_api"),eq(11L),eq(5L),eq("owner"),eq("success"),eq(1L),anyLong(),isNull(),eq(3L),eq(ref.admissionId()),eq("key-request-123"),eq(7L));
    }
    @Test void unknownOrUnsignedRedeemFailureNeverFallsBackToUserQueries(){when(peers.redeem(ref)).thenThrow(new Unavailable());assertThrows(Unavailable.class,()->service.query(ref));verifyNoInteractions(assets,tables,metrics,queries,audit);}
}
