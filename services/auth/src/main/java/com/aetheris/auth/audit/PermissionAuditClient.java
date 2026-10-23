package com.aetheris.auth.audit;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, observable best-effort audit side channel. Never changes the permission decision. */
@Component
public class PermissionAuditClient implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(PermissionAuditClient.class);
    private static final ObjectMapper JSON=new ObjectMapper();
    private final SignedHttpClient transport;
    private final ThreadPoolExecutor tasks=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),Thread.ofVirtual().name("auth-permission-audit-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong delivered=new AtomicLong(),failed=new AtomicLong(),dropped=new AtomicLong();
    public PermissionAuditClient(@Qualifier("authSystemTransport") SignedHttpClient transport){this.transport=transport;}
    public record DeliveryStats(long delivered,long failed,long dropped,int queued) { }
    public DeliveryStats deliveryStats(){return new DeliveryStats(delivered.get(),failed.get(),dropped.get(),tasks.getQueue().size());}

    public void record(PermissionEventRequest event) {
        byte[] raw=encode(event);if(raw!=null)deliver(raw);
    }
    public void recordAsync(PermissionEventRequest event,Duration timeout) {
        byte[] raw=encode(event);if(raw==null)return;
        if(timeout==null || timeout.isZero() || timeout.isNegative()){drop();return;}
        long expiry=System.nanoTime()+Math.min(timeout.compareTo(Duration.ofSeconds(2))>0?TimeUnit.SECONDS.toNanos(2):timeout.toNanos(),TimeUnit.SECONDS.toNanos(2));
        try {tasks.execute(()->{if(System.nanoTime()-expiry>=0)drop();else deliver(raw);});}
        catch(RejectedExecutionException full){drop();}
    }
    private byte[] encode(PermissionEventRequest event) {
        try {
            if(event==null)throw new IllegalArgumentException("Missing audit event");
            byte[] raw=JSON.writeValueAsBytes(event);
            if(raw.length>16*1024)throw new IllegalArgumentException("Audit event limit exceeded");
            return raw;
        }catch(JsonProcessingException | RuntimeException failure){failed.incrementAndGet();log.warn("Permission audit serialization failed: {}",failure.getClass().getSimpleName());return null;}
    }
    private void deliver(byte[] raw) {
        try {
            var response=transport.send(new SignedHttpClient.Request("system","POST","/internal/v2/audit/permission-events","system.permission-event.record",raw));
            if(response.status()!=202 || response.body().length!=0)throw new IllegalStateException("Unexpected audit acknowledgement");
            delivered.incrementAndGet();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed.incrementAndGet();log.warn("Permission audit delivery interrupted");}
        catch(RuntimeException failure){failed.incrementAndGet();log.warn("Permission audit delivery failed: {}",failure.getClass().getSimpleName());}
    }
    private void drop(){dropped.incrementAndGet();log.warn("Permission audit delivery dropped: queue unavailable or deadline elapsed");}
    @Override @PreDestroy public void close(){
        dropped.addAndGet(tasks.shutdownNow().size());
        try{if(!tasks.awaitTermination(3,TimeUnit.SECONDS))log.warn("Permission audit shutdown exceeded deadline");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
