package com.aetheris.ingestion.service;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded best-effort notifications; delivery failures never retry an ingestion execution. */
@Component
public class NotificationClient implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final SignedHttpClient transport;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),
            Thread.ofVirtual().inheritInheritableThreadLocals(false).name("ingestion-notification-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong delivered=new AtomicLong(),failed=new AtomicLong(),dropped=new AtomicLong();
    public NotificationClient(@Qualifier("ingestionSystemTransport") SignedHttpClient transport){this.transport=transport;}
    public record DeliveryStats(long delivered,long failed,long dropped,int queued) { }
    public DeliveryStats stats(){return new DeliveryStats(delivered.get(),failed.get(),dropped.get(),workers.getQueue().size());}
    public void notifyAdmin(Long taskId,String level,String title,String message) {
        final byte[] raw;
        try {
            if(taskId!=null && taskId<=0 || level==null || !Set.of("info","warning","critical").contains(level)
                    || title==null || title.isBlank() || title.length()>256 || message!=null && message.length()>1024)throw new IllegalArgumentException();
            Map<String,Object> body=new LinkedHashMap<>();body.put("recipient","admin");body.put("source","ingestion");
            body.put("sourceId",taskId);body.put("level",level);body.put("title",title);body.put("message",message);body.put("linkPath","/integration/sources");
            raw=JSON.writeValueAsBytes(body);if(raw.length>16*1024)throw new IllegalArgumentException();
        }catch(Exception invalid){failed.incrementAndGet();return;}
        long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        try {workers.execute(()->{
            if(Thread.currentThread().isInterrupted() || System.nanoTime()-expires>=0){dropped.incrementAndGet();return;}
            try {
                var response=transport.send(new SignedHttpClient.Request("system","POST","/internal/v2/notifications","system.notification.record",raw));
                if(response.status()!=202 || response.body().length!=0)throw new IllegalStateException();
                delivered.incrementAndGet();
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed.incrementAndGet();}
            catch(RuntimeException unavailable){failed.incrementAndGet();}
        });}catch(RejectedExecutionException full){dropped.incrementAndGet();}
    }
    @Override @PreDestroy public void close() {
        dropped.addAndGet(workers.shutdownNow().size());
        try {workers.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
