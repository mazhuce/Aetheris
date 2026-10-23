package com.aetheris.aiagent.llm;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
/** One run shared by HTTP cancellation, model rounds and tool calls. Never inherited by another thread. */
public final class AiRunContext {
    private static final ThreadLocal<AiRunContext> CURRENT=new ThreadLocal<>();
    private final AiBudgetLimits limits;
    private final long deadline;
    private boolean cancelled,completed;
    private int modelCalls,toolCalls;
    private long inputTokens,outputTokens;
    public AiRunContext(AiBudgetLimits limits){this.limits=limits;deadline=System.nanoTime()+limits.timeoutMillis()*1_000_000;}
    public static AiRunContext current(){return CURRENT.get();}
    public static void attach(AiRunContext context){CURRENT.set(context);}
    public static void clear(){CURRENT.remove();}
    public AiBudgetLimits limits(){return limits;}
    public long remainingMillis(){return Math.max(0,(deadline-System.nanoTime())/1_000_000);}
    public synchronized void check(){
        if(cancelled||completed||Thread.currentThread().isInterrupted()||remainingMillis()<=0)
            throw new LlmException(LlmException.Kind.UNAVAILABLE,"本轮请求已取消或超过处理时限",null);
        if(modelCalls>limits.modelCalls()||toolCalls>limits.toolCalls()||inputTokens>limits.inputTokens()||outputTokens>limits.outputTokens())throw exceeded();
    }
    public synchronized void cancel(){if(!completed)cancelled=true;}
    public synchronized <T> T commit(Supplier<T> save){check();T value=save.get();completed=true;return value;}
    public synchronized int beforeModel(int estimatedInput){
        check();
        if(++modelCalls>limits.modelCalls()||estimatedInput<0||(long)inputTokens+estimatedInput>limits.inputTokens()
                ||outputTokens>=limits.outputTokens())throw exceeded();
        inputTokens+=estimatedInput;
        return (int)Math.min(1024,limits.outputTokens()-outputTokens);
    }
    public synchronized void afterModel(int estimatedInput,int providerInput,int estimatedOutput,int providerOutput){
        check();
        inputTokens+=Math.max(0,providerInput-estimatedInput);
        outputTokens+=Math.max(estimatedOutput,Math.max(0,providerOutput));
        if(inputTokens>limits.inputTokens()||outputTokens>limits.outputTokens())throw exceeded();
    }
    public synchronized void beforeTool(){check();if(++toolCalls>limits.toolCalls())throw exceeded();}
    public synchronized int inputTokens(){return (int)Math.min(Integer.MAX_VALUE,inputTokens);}
    public synchronized int outputTokens(){return (int)Math.min(Integer.MAX_VALUE,outputTokens);}
    public static int estimate(String text){return text==null?0:text.getBytes(StandardCharsets.UTF_8).length;}
    private LlmException exceeded(){return new LlmException(LlmException.Kind.RATE_LIMITED,"本轮模型、工具或 token 预算已用尽，请缩小问题后重试",null);}
}
