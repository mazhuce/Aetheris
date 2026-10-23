package com.aetheris.metadata.fixedexecution;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FixedTransferWorkerTest {
    final FixedReceiptStore store=mock(FixedReceiptStore.class);
    final FixedTransferPeers peers=mock(FixedTransferPeers.class);
    final FixedTransferExecution execution=mock(FixedTransferExecution.class);
    FixedReceiptStore.Row row(FixedTransferManifest m){return new FixedReceiptStore.Row(99,11,0,1,2,m.sha256(),m.canonical(),"{}","executing","worker-test",1,OffsetDateTime.now().plusSeconds(120),OffsetDateTime.now().plusMinutes(30),null,null);}
    FixedReceiptStore.Row setup(FixedTransferTestPeers f){
        var m=f.install(11,5,6,"SELECT 1","business.output",s->s);var r=row(m);
        when(store.queued(anyInt())).thenReturn(List.of(99L));when(store.claim(eq(99L),anyString())).thenReturn(Optional.of(r),Optional.empty());
        when(store.live(r)).thenReturn(true);when(store.renew(r)).thenReturn(true);when(store.finish(any(),anyString(),any(),any())).thenReturn(true);
        when(peers.resolveAndAuthorize(r.submit())).thenReturn(m);when(execution.verify(any(FixedTransferManifest.class),eq("{}"))).thenReturn(new FixedTransferExecution.Prepared(null,null,"{}",m.definition()));return r;
    }
    static void until(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);assertTrue(condition.getAsBoolean());}
    @Test void onlyPhysicalCompletionReleasesTheSlotAndSuccessIsFenced()throws Exception{
        try(var f=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers,execution,1)){
            var r=setup(f);var done=new CountDownLatch(1);when(execution.execute(any())).thenAnswer(i->{assertNull(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes());return new FixedReceiptStore.Result(1,1,1);});
            when(store.finish(eq(r),eq("succeeded"),any(),isNull())).thenAnswer(i->{done.countDown();return true;});worker.tick();assertTrue(done.await(3,TimeUnit.SECONDS));until(()->worker.physicalActive()==0);
            verify(execution,times(1)).execute(any());verify(store).finish(eq(r),eq("succeeded"),eq(new FixedReceiptStore.Result(1,1,1)),isNull());
        }
    }
    @Test void permissionRevocationBeforeJdbcProducesKnownFailureWithNoSideEffect()throws Exception{
        try(var f=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers,execution,1)){
            var r=setup(f);when(peers.resolveAndAuthorize(r.submit())).thenThrow(new FixedTransferPeers.Rejected("EXECUTION_NOT_AUTHORIZED"));worker.tick();until(()->worker.physicalActive()==0);
            verifyNoInteractions(execution);verify(store).finish(r,"failed",null,"EXECUTION_NOT_AUTHORIZED");
        }
    }
    @Test void everyExceptionAfterJdbcBeginsIsUnknownEvenWhenTheAdapterClaimsRollback()throws Exception{
        try(var f=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers,execution,1)){
            var r=setup(f);when(execution.execute(any())).thenThrow(new IllegalArgumentException("driver says rolled back, not proof"));worker.tick();until(()->worker.physicalActive()==0);
            verify(store).finish(r,"unknown",null,"JDBC_OUTCOME_UNKNOWN");verify(execution,times(1)).execute(any());
        }
    }
    @Test void completionStoreFailureCannotResubmitJdbcOrTurnIntoSuccess()throws Exception{
        try(var f=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers,execution,1)){
            var r=setup(f);when(execution.execute(any())).thenReturn(new FixedReceiptStore.Result(1,1,1));when(store.finish(eq(r),eq("succeeded"),any(),isNull())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("commit response lost"));
            worker.tick();until(()->worker.physicalActive()==0);verify(execution,times(1)).execute(any());verify(store).finish(r,"unknown",null,"JDBC_OUTCOME_UNKNOWN");
        }
    }
    @Test void cancellationOfAnUninterruptibleJdbcDoesNotAdmitAnotherPhysicalExecution()throws Exception{
        try(var f=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers,execution,1)){
            var r=setup(f);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var interrupted=new AtomicBoolean();
            when(execution.execute(any())).thenAnswer(i->{entered.countDown();while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){interrupted.set(true);}return new FixedReceiptStore.Result(1,1,1);});
            try{
                worker.tick();assertTrue(entered.await(3,TimeUnit.SECONDS));when(store.renew(r)).thenReturn(false);worker.heartbeat();until(interrupted::get);
                assertEquals(1,worker.physicalActive());worker.tick();verify(store,times(1)).claim(eq(99L),anyString());verify(execution,times(1)).execute(any());
                verify(store).finish(r,"unknown",null,"WORKER_LEASE_LOST");
            }finally{release.countDown();until(()->worker.physicalActive()==0);}
            verify(store,never()).finish(eq(r),eq("succeeded"),any(),any());
        }
    }
    @Test void deadLeaseCannotReachJdbcAndWorkerCountIsBounded()throws Exception{
        assertThrows(IllegalArgumentException.class,()->new FixedTransferWorker(store,peers,execution,0));assertThrows(IllegalArgumentException.class,()->new FixedTransferWorker(store,peers,execution,5));
        try(var f=new FixedTransferTestPeers();var worker=new FixedTransferWorker(store,peers,execution,1)){
            var r=setup(f);when(store.live(r)).thenReturn(false);worker.tick();until(()->worker.physicalActive()==0);verifyNoInteractions(peers,execution);
        }
    }
    @Test void shutdownReturnsWithinItsBoundButHoldsThePhysicalSlotUntilJdbcActuallyExits()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var r=setup(f);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var physical=new AtomicReference<Thread>();
            var worker=new FixedTransferWorker(store,peers,execution,1);
            when(execution.execute(any())).thenAnswer(i->{physical.set(Thread.currentThread());entered.countDown();while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){ }return new FixedReceiptStore.Result(1,1,1);});
            try{
                worker.tick();assertTrue(entered.await(3,TimeUnit.SECONDS));long before=System.nanoTime();worker.close();assertTrue(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(5));
                assertEquals(1,worker.physicalActive());assertTrue(physical.get().isAlive());worker.tick();verify(store,times(1)).claim(eq(99L),anyString());
                verify(store).finish(r,"unknown",null,"WORKER_SHUTDOWN");verify(store,never()).finish(eq(r),eq("succeeded"),any(),any());
            }finally{release.countDown();worker.close();until(()->worker.physicalActive()==0);until(()->physical.get()==null||!physical.get().isAlive());}
        }
    }
}
