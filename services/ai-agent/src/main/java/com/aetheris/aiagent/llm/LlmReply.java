package com.aetheris.aiagent.llm;

/**
 * 模型回复。
 *
 * @param content      回复正文
 * @param model        实际响应的模型标识，可能与请求的不同（provider 侧路由）
 * @param promptTokens 本地累计输入预算单位（取保守估计与供应商usage较大值）
 * @param outputTokens 本地累计输出预算单位；不是供应商账单
 */
public record LlmReply(String content, String model, Integer promptTokens, Integer outputTokens,
        java.math.BigDecimal estimatedCost,String usageBasis) {
    public LlmReply(String content,String model,Integer input,Integer output){this(content,model,input,output,null,"unspecified");}
}
