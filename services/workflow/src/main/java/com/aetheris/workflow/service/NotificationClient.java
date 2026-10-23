package com.aetheris.workflow.service;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Best effort delivery has its own bounded budget and never retries a workflow execution. */
@Component
public class NotificationClient implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final SignedHttpClient transport;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),
            Thread.ofVirtual().inheritInheritableThreadLocals(false).name("workflow-notification-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong delivered=new AtomicLong(),failed=new AtomicLong(),dropped=new AtomicLong();
    public NotificationClient(@Qualifier("workflowSystemTransport") SignedHttpClient transport){this.transport=Objects.requireNonNull(transport);}
    public record DeliveryStats(long delivered,long failed,long dropped,int queued) { }
    public DeliveryStats stats(){return new DeliveryStats(delivered.get(),failed.get(),dropped.get(),workers.getQueue().size());}
    public void notifyAdmin(Long ruleId,String ignoredBizDate,String level,String title,String message){
        final byte[] raw;
        try {
            if(ruleId!=null&&ruleId<=0 || level==null||!Set.of("info","warning","critical").contains(level)
                    || title==null||title.isBlank()||title.length()>256 || message!=null&&message.length()>1024)throw new IllegalArgumentException();
            Map<String,Object> body=new LinkedHashMap<>();body.put("recipient","admin");body.put("source","workflow");body.put("sourceId",ruleId);
            body.put("level",level);body.put("title",title);body.put("message",message);body.put("linkPath","/development/workflow");
            raw=JSON.writeValueAsBytes(body);if(raw.length>16*1024)throw new IllegalArgumentException();
        }catch(Exception invalid){failed.incrementAndGet();return;}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        try{workers.execute(()->{
            long remaining=deadline-System.nanoTime();if(remaining<=0||Thread.currentThread().isInterrupted()){dropped.incrementAndGet();return;}
            CompletableFuture<SignedHttpClient.Response> pending=null;
            try{
                pending=transport.sendAsync(new SignedHttpClient.Request("system","POST","/internal/v2/notifications","system.notification.record",raw));
                var response=pending.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if(response.status()!=202||response.body().length!=0)throw new IllegalStateException();delivered.incrementAndGet();
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();failed.incrementAndGet();}
            catch(Exception unavailable){failed.incrementAndGet();}
            finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
        });}catch(RejectedExecutionException full){dropped.incrementAndGet();}
    }
    @Override @PreDestroy public void close(){
        dropped.addAndGet(workers.shutdownNow().size());
        try{if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Workflow notification worker did not terminate");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Workflow notification shutdown interrupted");}
    }
}
