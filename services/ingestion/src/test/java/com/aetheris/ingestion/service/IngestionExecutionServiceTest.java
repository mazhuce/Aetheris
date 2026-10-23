package com.aetheris.ingestion.service;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.ingestion.security.V2IngestionFixture;
import com.aetheris.security.servlet.TrustedUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IngestionExecutionServiceTest {
    final IngestionExecutionStore source=mock(IngestionExecutionStore.class);
    final MetadataIngestionClient client=mock(MetadataIngestionClient.class);
    final NotificationClient notification=mock(NotificationClient.class);
    final ExecutionRecoveryService recovery=mock(ExecutionRecoveryService.class);
    final IngestionExecutionService service=new IngestionExecutionService(source,client,notification,recovery);
    IngestionRun run(){var r=new IngestionRun();r.setId(11L);r.setTaskId(1L);r.setStatus("running");return r;}
    IngestionExecutionStore.Claim claim(boolean reused){return new IngestionExecutionStore.Claim(run(),2,"a".repeat(64),reused);}
    TrustedUser user(V2IngestionFixture f) throws Exception {var current=new AtomicReference<TrustedUser>();f.request(r->current.set(TrustedUser.require(r)));return current.get();}
    @Test void lostTransferResponseIsUnknownAndCannotBeReportedAsKnownFailure() throws Exception {
        try(var f=new V2IngestionFixture()) {
            when(source.claimManual(eq(1L),any(),isNull(),isNull())).thenReturn(claim(false));
            when(client.execute(any(),any())).thenThrow(new MetadataIngestionClient.IngestionExecutionException("lost"));
            assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->service.executeManual(1L,user(f),null,null)).outcomeUnknown());
            var saved=ArgumentCaptor.forClass(IngestionRun.class);verify(recovery).finish(saved.capture());assertEquals("unknown",saved.getValue().getStatus());assertEquals("TRANSFER_OUTCOME_UNKNOWN",saved.getValue().getFailureCode());assertNotNull(saved.getValue().getErrorMessage());assertFalse(saved.getValue().getErrorMessage().isBlank());assertFalse(saved.getValue().getErrorMessage().contains("lost"));
            verify(client).execute(any(),any());verify(notification).notifyAdmin(eq(1L),any(),any(),any());
        }
    }
    @Test void obsoleteScheduledVersionCannotExecuteTransfer() {
        when(source.claimScheduled(1L,8,"schedule:8:1")).thenThrow(IngestionExecutionStore.conflict());
        assertThrows(IllegalStateException.class,()->service.executeScheduled(1L,8,"schedule:8:1"));verifyNoInteractions(client,recovery);
    }
    @Test void recordsRealTransferCountsUsingRequiredRecoveryAndSingleReceiptBinding() throws Exception {
        try(var f=new V2IngestionFixture()) {
            when(source.claimManual(eq(1L),any(),isNull(),isNull())).thenReturn(claim(false));when(recovery.finish(any())).thenReturn(true);
            when(client.execute(any(),any())).thenAnswer(i->{i.getArgument(1,LongConsumer.class).accept(99);return new MetadataIngestionClient.TransferResult(12,12,45);});
            var result=service.executeManual(1L,user(f),null,null);assertEquals("success",result.getStatus());assertEquals(12L,result.getRowsWritten());assertEquals(45L,result.getDurationMs());assertNotNull(result.getFinishedAt());
            verify(source).bindReceipt(11,99);verify(recovery).finish(result);
            var actor=ArgumentCaptor.forClass(IngestionActor.class);verify(source).claimManual(eq(1L),actor.capture(),isNull(),isNull());assertEquals(42,actor.getValue().uid());assertEquals(3,actor.getValue().accessVersion());assertEquals("alice",actor.getValue().username());
        }
    }
    @Test void persistsFailedRunAndNeverReportsSuccess() throws Exception {
        try(var f=new V2IngestionFixture()) {
            when(source.claimManual(eq(1L),any(),isNull(),isNull())).thenReturn(claim(false));
            when(client.execute(any(),any())).thenThrow(new MetadataIngestionClient.IngestionExecutionException("confirmed failure",false));
            assertFalse(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->service.executeManual(1L,user(f),null,null)).outcomeUnknown());
            var saved=ArgumentCaptor.forClass(IngestionRun.class);verify(recovery).finish(saved.capture());assertEquals("failed",saved.getValue().getStatus());assertEquals("UPSTREAM_TRANSFER_FAILED",saved.getValue().getFailureCode());
        }
    }
    @Test void rejectsIncompleteLegacyTaskBeforeAnyTransfer() throws Exception {
        try(var f=new V2IngestionFixture()) {
            when(source.claimManual(eq(1L),any(),isNull(),isNull())).thenThrow(FixedJson.invalid());
            assertThrows(IllegalArgumentException.class,()->service.executeManual(1L,user(f),null,null));verifyNoInteractions(client,recovery);
        }
    }
    @Test void reusesCompletedRunForSameIdempotencyKeyWithoutResubmission() throws Exception {
        try(var f=new V2IngestionFixture()) {
            var reused=claim(true);reused.run().setStatus("success");when(source.claimManual(eq(1L),any(),eq("request-42"),isNull())).thenReturn(reused);
            assertSame(reused.run(),service.executeManual(1L,user(f),"request-42",null));verifyNoInteractions(client,recovery);
        }
    }
    @Test void staleCompletionAndMissingProductionRecoveryNeverUseAnUpdateFallback() throws Exception {
        assertThrows(NullPointerException.class,()->new IngestionExecutionService(source,client,notification,null));
        try(var f=new V2IngestionFixture()) {
            when(source.claimManual(eq(1L),any(),isNull(),isNull())).thenReturn(claim(false));when(client.execute(any(),any())).thenReturn(new MetadataIngestionClient.TransferResult(1,1,1));
            when(recovery.finish(any())).thenReturn(false);assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->service.executeManual(1L,user(f),null,null)).outcomeUnknown());
            verify(client).execute(any(),any());verify(recovery,times(2)).finish(any());
        }
    }
}
