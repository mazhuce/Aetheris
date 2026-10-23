package com.aetheris.aiagent.service;

import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.security.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.*;
import org.springframework.web.context.request.async.DeferredResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Bounded admission; only an immutable verified identity snapshot crosses threads. */
@Component
public class AiRequestExecutor implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final AiBudgetLimits limits;
    public AiRequestExecutor(AiBudgetLimits limits) {
        this.limits=limits;
        BlockingQueue<Runnable> queue=limits.queueCapacity()==0?new SynchronousQueue<>():new ArrayBlockingQueue<>(limits.queueCapacity());
        workers=new ThreadPoolExecutor(limits.workers(),limits.workers(),0,TimeUnit.MILLISECONDS,queue,
                runnable->{Thread thread=new Thread(runnable,"ai-request");thread.setDaemon(true);return thread;},
                new ThreadPoolExecutor.AbortPolicy());
    }
    public DeferredResult<ResponseEntity<Map<String,Object>>> submit(HttpServletRequest original,
            Long conversationId,Supplier<ResponseEntity<Map<String,Object>>> action) {
        AiProofContext.Snapshot snapshot=snapshot(original);
        AiRunContext run=new AiRunContext(limits);
        var result=new DeferredResult<ResponseEntity<Map<String,Object>>>(limits.timeoutMillis());
        var future=new AtomicReference<Future<?>>();
        Runnable cancel=()->{run.cancel();Future<?> task=future.get();if(task!=null)task.cancel(true);workers.purge();};
        result.onTimeout(()->{cancel.run();result.setResult(error(504,"AI 处理超时，本轮已停止",conversationId));});
        result.onError(failure->cancel.run());
        result.onCompletion(cancel);
        try {
            future.set(workers.submit(()->{
                RequestContextHolder.resetRequestAttributes();
                AiRunContext.clear();
                AiProofContext.clear();
                try {
                    AiProofContext.attach(snapshot);AiRunContext.attach(run);
                    run.check();
                    ResponseEntity<Map<String,Object>> response=action.get();
                    if(!result.isSetOrExpired())result.setResult(run.remainingMillis()<=0
                            ?error(504,"AI 处理超时，本轮已停止",conversationId):response);
                } catch(LlmException failure) {
                    if(!result.isSetOrExpired())result.setResult(error(run.remainingMillis()<=0?504:
                            failure.kind()==LlmException.Kind.RATE_LIMITED?429:502,failure.getMessage(),conversationId));
                } catch(RuntimeException failure) {
                    if(!result.isSetOrExpired())result.setResult(error(500,"AI 请求处理失败",conversationId));
                } finally {
                    AiRunContext.clear();AiProofContext.clear();RequestContextHolder.resetRequestAttributes();
                }
            }));
            if(result.isSetOrExpired())cancel.run();
        } catch(RejectedExecutionException full) {
            run.cancel();result.setResult(error(429,"AI 服务当前处理容量已满，请稍后重试",conversationId));
        }
        return result;
    }
    static AiProofContext.Snapshot snapshot(HttpServletRequest request) {
        return AiProofContext.snapshot(request);
    }
    static ResponseEntity<Map<String,Object>> error(int status,String message,Long conversationId) {
        Map<String,Object> body=new LinkedHashMap<>();body.put("role","assistant");body.put("content",message);
        if(conversationId!=null)body.put("conversationId",conversationId);
        return ResponseEntity.status(status).body(body);
    }
    @Override @jakarta.annotation.PreDestroy public void close(){workers.shutdownNow();try{if(!workers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("AI worker shutdown exceeded deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("AI worker shutdown interrupted");}}
}
