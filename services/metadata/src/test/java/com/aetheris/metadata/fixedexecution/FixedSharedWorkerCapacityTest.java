package com.aetheris.metadata.fixedexecution;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FixedSharedWorkerCapacityTest {
    @Test void qualityAndTransferShareOnePhysicalPoolAndCancellationCannotCreateAThirdCall()throws Exception{
        var store=mock(FixedReceiptStore.class);var transferPeers=mock(FixedTransferPeers.class);var transferExecution=mock(FixedTransferExecution.class);var qualityPeers=mock(FixedQualityPeers.class);var qualityExecution=mock(FixedQualityExecution.class);
        try(var q=new FixedQualityTestPeers();var t=new FixedTransferTestPeers()){
            var qm=q.install(11,5,"sql","SELECT COUNT(*) FROM business.input",0,s->s);var tm=t.install(11,5,6,"SELECT 1","business.output",s->s);
            var qr=new FixedReceiptStore.Row(99,11,0,1,2,qm.sha256(),qm.canonical(),"{}","executing","q-worker",1,OffsetDateTime.now().plusSeconds(120),OffsetDateTime.now().plusMinutes(30),null,null,FixedExecutionOperation.QUALITY);
            var tr=new FixedReceiptStore.Row(100,11,0,1,2,tm.sha256(),tm.canonical(),"{}","executing","t-worker",1,OffsetDateTime.now().plusSeconds(120),OffsetDateTime.now().plusMinutes(30),null,null);
            when(store.queued(anyInt())).thenReturn(List.of(99L,100L,101L));when(store.claim(eq(99L),anyString())).thenReturn(Optional.of(qr));when(store.claim(eq(100L),anyString())).thenReturn(Optional.of(tr));when(store.live(any())).thenReturn(true);when(store.renew(any())).thenReturn(true);when(store.finish(any(),anyString(),any(),any())).thenReturn(true);
            when(transferPeers.resolveAndAuthorize(any())).thenReturn(tm);when(qualityPeers.resolveAndAuthorize(any())).thenReturn(qm);
            when(transferExecution.verify(any(),eq("{}"))).thenReturn(new FixedTransferExecution.Prepared(null,null,"{}",tm.definition()));when(qualityExecution.verify(any(),eq("{}"))).thenReturn("{}");
            var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var concurrent=new AtomicInteger();var peak=new AtomicInteger();
            Runnable block=()->{int n=concurrent.incrementAndGet();peak.accumulateAndGet(n,Math::max);entered.countDown();try{while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){ }}finally{concurrent.decrementAndGet();}};
            when(transferExecution.execute(any())).thenAnswer(i->{block.run();return new FixedReceiptStore.Result(1,1,1);});when(qualityExecution.execute(any(),eq("{}"))).thenAnswer(i->{block.run();return new FixedQualityResult(1,1);});
            var directory=new FixedExecutionAdapters(transferPeers,transferExecution,qualityPeers,qualityExecution);
            try(var worker=new FixedTransferWorker(store,directory,2)){
                try{worker.tick();assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(2,worker.physicalActive());assertEquals(2,peak.get());verify(store,never()).claim(eq(101L),anyString());
                    when(store.renew(qr)).thenReturn(false);worker.heartbeat();worker.tick();assertEquals(2,worker.physicalActive());verify(store,never()).claim(eq(101L),anyString());
                }finally{release.countDown();FixedTransferWorkerTest.until(()->worker.physicalActive()==0);}
                verify(store).finish(qr,"unknown",null,"WORKER_LEASE_LOST");verify(store,never()).finish(eq(qr),eq("succeeded"),any(),any());verify(transferExecution,times(1)).execute(any());verify(qualityExecution,times(1)).execute(any(),eq("{}"));
            }
        }
    }
}
