package com.aetheris.aiagent.llm;
/** Local admission estimates, not provider billing guarantees. */
public record AiBudgetLimits(int modelCalls,int toolCalls,int inputTokens,int outputTokens,
        int toolResultChars,long timeoutMillis,int workers,int queueCapacity) {
    public AiBudgetLimits {
        if(modelCalls<1||modelCalls>20||toolCalls<0||toolCalls>50||inputTokens<256||inputTokens>200000
                ||outputTokens<1||outputTokens>32000||toolResultChars<128||toolResultChars>100000
                ||timeoutMillis<50||timeoutMillis>180000||workers<1||workers>32||queueCapacity<0||queueCapacity>100)
            throw new IllegalArgumentException("AI budget configuration outside supported bounds");
    }
    public static AiBudgetLimits defaults(){return new AiBudgetLimits(6,10,128000,4096,12000,60000,4,8);}
}
