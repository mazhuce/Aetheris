package com.aetheris.metadata.fixedexecution;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Slots are released only after physical execution returns, never when a Future is marked cancelled. */
@Component
public class FixedTransferWorker implements AutoCloseable {
    private final FixedReceiptStore store;private final FixedExecutionAdapters adapters;private final java.time.Clock clock;
    private final ThreadPoolExecutor pool;private final Semaphore slots;private final String owner=UUID.randomUUID().toString();
    private final AtomicBoolean closed=new AtomicBoolean();private final ConcurrentMap<Long,Work> active=new ConcurrentHashMap<>();
    private static final class Work {final FixedReceiptStore.Row row;volatile Thread thread;volatile java.time.Instant deadline;volatile long deadlineNanos;final AtomicBoolean cancelled=new AtomicBoolean();Work(FixedReceiptStore.Row row){this.row=row;}}
    @Autowired public FixedTransferWorker(FixedReceiptStore store,FixedExecutionAdapters adapters,
            @Value("${aetheris.fixed-execution.workers:2}") int workers){this(store,adapters,workers,java.time.Clock.systemUTC());}
    FixedTransferWorker(FixedReceiptStore store,FixedExecutionAdapters adapters,int workers,java.time.Clock clock){
        this.clock=Objects.requireNonNull(clock);
        if(workers<1||workers>4)throw new IllegalArgumentException("Fixed worker count must be 1..4");
        this.store=store;this.adapters=adapters;this.slots=new Semaphore(workers);
        this.pool=new ThreadPoolExecutor(workers,workers,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofPlatform().name("fixed-transfer-",0).daemon(true).inheritInheritableThreadLocals(false).factory(),new ThreadPoolExecutor.AbortPolicy());
    }
    public FixedTransferWorker(FixedReceiptStore store,FixedTransferPeers peers,FixedTransferExecution execution,int workers){this(store,FixedExecutionAdapters.transferOnly(peers,execution),workers);}
    @Scheduled(fixedDelayString="${aetheris.fixed-execution.poll-ms:1000}")
    public void tick(){
        if(closed.get())return;
        try{store.expire();for(long id:store.queued(slots.availablePermits())){
            if(closed.get()||!slots.tryAcquire())break;
            Work work=null;
            try{
                var claimed=store.claim(id,owner);if(claimed.isEmpty()){slots.release();continue;}
                work=new Work(claimed.get());active.put(id,work);Work dispatch=work;pool.execute(()->run(dispatch));
            }catch(RuntimeException unavailable){
                if(work!=null){mark(work,"unknown","DISPATCH_UNCONFIRMED");active.remove(id,work);}slots.release();
            }
        }}catch(RuntimeException unavailable){/* A DB outage leaves durable queued entries and fenced active leases. */}
    }
    private void run(Work work){
        work.thread=Thread.currentThread();boolean jdbcStarted=false;
        try{
            ensure(work);var adapter=adapters.get(work.row.operation());var manifest=adapter.resolveAndAuthorize(work.row.submission());ensure(work);
            if(!manifest.submission().manifestSha256().equals(work.row.hash())||!FixedJson.parse(manifest.canonical()).equals(FixedJson.parse(work.row.manifest())))throw new FixedTransferPeers.Rejected("SOURCE_MANIFEST_CHANGED");
            var prepared=adapter.verify(manifest,work.row.execution());ensure(work);
            FixedExecutionAdapter.DispatchBudget budget=null;
            if(work.row.operation()==FixedExecutionOperation.SQL){
                var sql=FixedSqlManifest.parse(manifest.canonical(),manifest.submission().manifestSha256(),manifest.submission());
                java.time.Instant now=clock.instant(),deadline=now.plusSeconds(sql.definition().timeoutSeconds());
                var sourceDeadline=manifest.sourceLease().expiresAt().minusSeconds(5);if(sourceDeadline.isBefore(deadline))deadline=sourceDeadline;
                var receiptDeadline=work.row.deadline().toInstant().minusSeconds(5);if(receiptDeadline.isBefore(deadline))deadline=receiptDeadline;
                var remaining=java.time.Duration.between(now,deadline);if(remaining.getSeconds()<1)throw new FixedTransferPeers.Rejected("EXECUTION_BUDGET_EXHAUSTED");
                work.deadlineNanos=System.nanoTime()+remaining.toNanos();work.deadline=deadline;budget=new FixedExecutionAdapter.DispatchBudget(deadline);
            }
            ensure(work);jdbcStarted=true;var result=adapter.execute(prepared,budget);ensure(work);
            if(!store.finish(work.row,"succeeded",result,null))mark(work,"unknown","COMPLETION_UNCONFIRMED");
        }catch(Throwable failure){
            String state=jdbcStarted||work.cancelled.get()||Thread.currentThread().isInterrupted()||failure instanceof CancellationException?"unknown":"failed";
            String code=jdbcStarted?"JDBC_OUTCOME_UNKNOWN":failure instanceof FixedTransferPeers.Rejected rejected?rejected.code():state.equals("unknown")?"WORKER_CANCELLED":"ADMISSION_UNAVAILABLE";
            mark(work,state,code);
        }finally{active.remove(work.row.id(),work);slots.release();}
    }
    private boolean expired(Work work){return work.deadline!=null&&(!clock.instant().isBefore(work.deadline)||System.nanoTime()-work.deadlineNanos>=0);}
    private void ensure(Work work){if(expired(work)||closed.get()||work.cancelled.get()||Thread.currentThread().isInterrupted()||!store.live(work.row))throw new CancellationException();}
    private void mark(Work work,String state,String code){try{store.finish(work.row,state,null,code);}catch(RuntimeException unavailable){/* Expiry persists unknown when the store becomes available. */}}
    @Scheduled(fixedDelayString="${aetheris.fixed-execution.heartbeat-ms:5000}")
    public void heartbeat(){
        if(closed.get())return;
        for(Work work:active.values())try{if(expired(work))cancel(work,"WORKER_DEADLINE_EXPIRED");else if(!store.renew(work.row))cancel(work,"WORKER_LEASE_LOST");}catch(RuntimeException unavailable){cancel(work,"WORKER_STORE_UNAVAILABLE");}
        try{store.expire();}catch(RuntimeException unavailable){ }
    }
    private void cancel(Work work,String code){work.cancelled.set(true);mark(work,"unknown",code);Thread thread=work.thread;if(thread!=null)thread.interrupt();}
    public int physicalActive(){return active.size();}
    @Override @PreDestroy public void close(){
        if(!closed.compareAndSet(false,true))return;
        active.values().forEach(work->cancel(work,"WORKER_SHUTDOWN"));pool.shutdownNow();
        try{pool.awaitTermination(2500,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
