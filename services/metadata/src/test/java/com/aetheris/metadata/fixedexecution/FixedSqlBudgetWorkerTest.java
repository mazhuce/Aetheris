package com.aetheris.metadata.fixedexecution;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class FixedSqlBudgetWorkerTest {
    static class MutableClock extends Clock {final AtomicReference<Instant> now=new AtomicReference<>(Instant.now());public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return now.get();}}
    FixedReceiptStore.Row row(FixedSqlManifest m,Instant deadline){return new FixedReceiptStore.Row(99,m.binding().runId(),m.binding().attemptId(),m.binding().fence(),2,m.sha256(),m.canonical(),"{}","executing","worker",1,OffsetDateTime.now().plusSeconds(120),deadline.atOffset(ZoneOffset.UTC),null,null,FixedExecutionOperation.SQL);}
    @Test void lessThanOneSecondBudgetFailsBeforePhysicalJdbcDispatch()throws Exception{
        try(var p=new FixedSqlTestPeers()){
            var clock=new MutableClock();var m=p.install(11,8,"INSERT INTO t SELECT 1",30,s->s);var row=row(m,clock.instant().plusMillis(5999));var store=mock(FixedReceiptStore.class);var peers=mock(FixedSqlPeers.class);var execution=mock(FixedSqlExecution.class);
            when(store.queued(anyInt())).thenReturn(List.of(99L));when(store.claim(eq(99L),anyString())).thenReturn(Optional.of(row));when(store.live(any())).thenReturn(true);when(store.finish(any(),any(),any(),any())).thenReturn(true);
            when(peers.resolveAndAuthorize(any())).thenReturn(new FixedSqlPeers.Resolved(m,new FixedExecutionAdapter.SourceLease(clock.instant().plusSeconds(90))));when(execution.verify(any(),eq("{}"))).thenReturn("{}");
            try(var worker=new FixedTransferWorker(store,FixedExecutionAdapters.sqlOnly(peers,execution),1,clock)){worker.tick();FixedTransferWorkerTest.until(()->worker.physicalActive()==0);verify(execution,never()).execute(any(),any(),any());verify(store).finish(row,"failed",null,"EXECUTION_BUDGET_EXHAUSTED");}
        }
    }
    @Test void sqlQualityAndTransferSharePhysicalPermitsEvenAfterSqlDeadlineCancellation()throws Exception{
        try(var p=new FixedSqlTestPeers();var q=new FixedQualityTestPeers()){
            var clock=new MutableClock();var m=p.install(11,8,"INSERT INTO t SELECT 1",30,s->s);var sqlRow=row(m,clock.instant().plusSeconds(20));var qm=q.install(12,8,"sql","SELECT COUNT(*) FROM business.input",0,s->s);
            var qualityRow=new FixedReceiptStore.Row(100,12,0,1,2,qm.sha256(),qm.canonical(),"{}","executing","quality-worker",1,OffsetDateTime.now().plusSeconds(120),OffsetDateTime.now().plusMinutes(30),null,null,FixedExecutionOperation.QUALITY);
            var store=mock(FixedReceiptStore.class);var sqlPeers=mock(FixedSqlPeers.class);var sqlExecution=mock(FixedSqlExecution.class);var qualityPeers=mock(FixedQualityPeers.class);var qualityExecution=mock(FixedQualityExecution.class);
            when(store.queued(anyInt())).thenReturn(List.of(99L,100L,101L));when(store.claim(eq(99L),anyString())).thenReturn(Optional.of(sqlRow));when(store.claim(eq(100L),anyString())).thenReturn(Optional.of(qualityRow));when(store.live(any())).thenReturn(true);when(store.renew(any())).thenReturn(true);when(store.finish(any(),any(),any(),any())).thenReturn(true);
            when(sqlPeers.resolveAndAuthorize(any())).thenReturn(new FixedSqlPeers.Resolved(m,new FixedExecutionAdapter.SourceLease(clock.instant().plusSeconds(18))));when(sqlExecution.verify(any(),eq("{}"))).thenReturn("{}");when(qualityPeers.resolveAndAuthorize(any())).thenReturn(qm);when(qualityExecution.verify(any(),eq("{}"))).thenReturn("{}");
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var active=new AtomicInteger();var peak=new AtomicInteger();var budget=new AtomicReference<Instant>();
            Runnable block=()->{int count=active.incrementAndGet();peak.accumulateAndGet(count,Math::max);entered.countDown();try{while(release.getCount()>0)try{release.await();}catch(InterruptedException deliberatelyUnsupportedDriverCancellation){}}finally{active.decrementAndGet();}};
            when(sqlExecution.execute(any(),eq("{}"),any())).thenAnswer(i->{budget.set(((FixedExecutionAdapter.DispatchBudget)i.getArgument(2)).deadline());block.run();return new FixedSqlResult(1L,1);});when(qualityExecution.execute(any(),eq("{}"))).thenAnswer(i->{block.run();return new FixedQualityResult(1,1);});
            var directory=new FixedExecutionAdapters(mock(FixedTransferPeers.class),mock(FixedTransferExecution.class),qualityPeers,qualityExecution,sqlPeers,sqlExecution);
            try(var worker=new FixedTransferWorker(store,directory,2,clock)){
                try{worker.tick();assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(clock.instant().plusSeconds(13),budget.get());assertEquals(2,peak.get());
                    clock.now.set(clock.instant().plusSeconds(14));worker.heartbeat();worker.tick();assertEquals(2,worker.physicalActive());verify(store,never()).claim(eq(101L),anyString());verify(store).finish(sqlRow,"unknown",null,"WORKER_DEADLINE_EXPIRED");
                }finally{release.countDown();FixedTransferWorkerTest.until(()->worker.physicalActive()==0);}
                verify(store,never()).finish(eq(sqlRow),eq("succeeded"),any(),any());verify(sqlExecution,times(1)).execute(any(),any(),any());
            }
        }
    }
}
