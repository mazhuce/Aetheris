package com.aetheris.aiagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Spring AI 的 DeepSeek 适配器。
 *
 * <p>替代了原先在 controller 里手写的 RestClient 调用。改用 Spring AI 的收益不只是少写
 * HTTP 代码：它带来了统一的模型接口、可观测性埋点，以及 {@code spring-ai-autoconfigure-model-tool}
 * 提供的工具调用能力 —— 后者是后续做 DataAgent（让模型自己查元数据、执行 SQL）的基础。
 *
 * <p>工具循环由本适配器逐轮驱动；关闭框架内部工具执行，生产禁自动重试，
 * 每轮显式执行预算检查并预留输出；总请求取消由有界异步执行器传入。
 *
 * <p>不加 {@code @Component}：需要注入工具对象列表，由 {@code AgentConfig} 显式装配，
 * 避免 Spring 对 {@code List<Object>} 参数做出意料之外的自动装配。
 */
public class SpringAiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAiLlmClient.class);

    private final ChatModel chatModel;
    private final String configuredModel;
    private final boolean apiKeyPresent;
    /**
     * 暴露给模型的工具对象，由 {@code AgentConfig} 显式装配。
     *
     * <p>用 {@code List<Object>} 而非具体类型：本适配器属于 llm 包，不应依赖 agent 包的工具类；
     * 测试时传空列表即可隔离工具调用。
     */
    private final List<Object> toolObjects;

    public SpringAiLlmClient(
            ChatModel chatModel,
            String configuredModel,
            String apiKey,
            List<Object> toolObjects) {
        this.chatModel = chatModel;
        this.configuredModel = configuredModel;
        this.apiKeyPresent = StringUtils.hasText(apiKey);
        this.toolObjects = toolObjects == null ? List.of() : List.copyOf(toolObjects);
    }

    private AiBudgetLimits limits=AiBudgetLimits.defaults();
    private java.math.BigDecimal inputPrice=java.math.BigDecimal.ZERO,outputPrice=java.math.BigDecimal.ZERO;
    public void setEstimatedPrices(java.math.BigDecimal input,java.math.BigDecimal output) {
        if(input.signum()<0||output.signum()<0)throw new IllegalArgumentException("AI estimated prices must be nonnegative");
        inputPrice=input;outputPrice=output;
    }
    public SpringAiLlmClient(ChatModel model,String configured,String key,List<Object> tools,AiBudgetLimits limits) {
        this(model,configured,key,tools);this.limits=limits;
    }
    @Override public LlmReply chat(List<LlmMessage> messages){return execute(messages,null,false);}
    @Override public LlmReply chatWithTools(List<LlmMessage> messages,String systemPrompt){return execute(messages,systemPrompt,true);}
    private LlmReply execute(List<LlmMessage> messages,String systemPrompt,boolean toolsEnabled) {
        requireApiKey();
        AiRunContext run=AiRunContext.current();
        boolean local=run==null;
        if(local){run=new AiRunContext(limits);AiRunContext.attach(run);}
        try {
            List<Message> history=new ArrayList<>();
            if(StringUtils.hasText(systemPrompt))history.add(new org.springframework.ai.chat.messages.SystemMessage(systemPrompt));
            history.addAll(toSpringAiMessages(messages));
            var callbacks=toolsEnabled?List.of(org.springframework.ai.support.ToolCallbacks.from(toolObjects.toArray()))
                    :List.<org.springframework.ai.tool.ToolCallback>of();
            var byName=new java.util.HashMap<String,org.springframework.ai.tool.ToolCallback>();
            for(var callback:callbacks) {
                if(byName.put(callback.getToolDefinition().name(),callback)!=null)
                    throw new IllegalArgumentException("Duplicate tool registration");
            }
            while(true) {
                int estimate=estimateInput(history,callbacks);
                int outputReservation=run.beforeModel(estimate);
                var options=org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
                        .internalToolExecutionEnabled(false).toolCallbacks(callbacks).maxTokens(outputReservation).build();
                ChatResponse response=chatModel.call(new Prompt(history,options));
                run.check();
                if(response==null||response.getResult()==null||response.getResult().getOutput()==null)
                    throw new LlmException(LlmException.Kind.UNKNOWN,"AI 未返回有效回复",null);
                AssistantMessage output=response.getResult().getOutput();
                int estimatedOutput=AiRunContext.estimate(output.getText());
                for(var call:output.getToolCalls())estimatedOutput+=AiRunContext.estimate(call.arguments())+AiRunContext.estimate(call.name())+32;
                run.afterModel(estimate,nullable(promptTokens(response)),estimatedOutput,nullable(outputTokens(response)));
                if(!output.hasToolCalls()) {
                    if(!StringUtils.hasText(output.getText()))throw new LlmException(LlmException.Kind.UNKNOWN,"AI 未返回有效回复",null);
                    java.math.BigDecimal cost=inputPrice.signum()==0&&outputPrice.signum()==0?null:
                            inputPrice.multiply(java.math.BigDecimal.valueOf(run.inputTokens()))
                            .add(outputPrice.multiply(java.math.BigDecimal.valueOf(run.outputTokens())))
                            .divide(java.math.BigDecimal.valueOf(1000000),6,java.math.RoundingMode.UP);
                    return new LlmReply(output.getText(),resolveModel(response),run.inputTokens(),run.outputTokens(),cost,"conservative_local_estimate");
                }
                history.add(output);
                List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> results=new ArrayList<>();
                for(var call:output.getToolCalls()) {
                    run.beforeTool();
                    var callback=byName.get(call.name());
                    if(callback==null||!"function".equals(call.type())||call.arguments()==null||call.arguments().length()>16000)
                        throw new LlmException(LlmException.Kind.UNKNOWN,"模型请求了未知或非法工具，已停止执行",null);
                    String result=callback.call(call.arguments());
                    run.check();
                    if(result==null||result.length()>run.limits().toolResultChars())
                        throw new LlmException(LlmException.Kind.RATE_LIMITED,"工具结果超过本轮容量限制，请缩小查询范围",null);
                    results.add(new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse(call.id(),call.name(),result));
                }
                history.add(new org.springframework.ai.chat.messages.ToolResponseMessage(results));
            }
        } catch(LlmException failure){throw failure;}
        catch(RuntimeException failure) {
            // Do not log raw provider payloads or user/tool data.
            log.warn("AI invocation failed: {}",failure.getClass().getSimpleName());
            throw new LlmException(classify(failure),friendlyMessage(classify(failure)),failure);
        } finally { if(local)AiRunContext.clear(); }
    }
    private int nullable(Integer value){return value==null?0:Math.max(0,value);}
    private int estimateInput(List<Message> history,List<org.springframework.ai.tool.ToolCallback> callbacks) {
        long bytes=0;
        for(Message message:history) {
            bytes+=AiRunContext.estimate(message.getText())+32;
            if(message instanceof AssistantMessage assistant)
                for(var call:assistant.getToolCalls())bytes+=AiRunContext.estimate(call.arguments())+AiRunContext.estimate(call.name())+32;
            if(message instanceof org.springframework.ai.chat.messages.ToolResponseMessage response)
                for(var result:response.getResponses())bytes+=AiRunContext.estimate(result.responseData())+32;
        }
        for(var callback:callbacks) {
            var definition=callback.getToolDefinition();
            bytes+=AiRunContext.estimate(definition.name())+AiRunContext.estimate(definition.description())
                    +AiRunContext.estimate(definition.inputSchema())+64;
        }
        return bytes>Integer.MAX_VALUE?Integer.MAX_VALUE:(int)bytes;
    }

    /** 提前失败并给出可操作的提示，而不是让请求打到 provider 拿一个 401。 */
    private void requireApiKey() {
        if (!apiKeyPresent) {
            throw new LlmException(
                    LlmException.Kind.NOT_CONFIGURED,
                    "AI 服务未配置访问密钥，请设置 DEEPSEEK_API_KEY",
                    null);
        }
    }

    @Override
    public String model() {
        return configuredModel;
    }

    private List<Message> toSpringAiMessages(List<LlmMessage> messages) {
        List<Message> converted = new ArrayList<>(messages.size());
        for (LlmMessage message : messages) {
            if(message==null||!java.util.Set.of("user","assistant").contains(message.role()))
                throw new IllegalArgumentException("Only trusted user/assistant history is supported");
            converted.add(message.isUser()
                    ? new UserMessage(message.content())
                    : new AssistantMessage(message.content()));
        }
        return converted;
    }

    private String resolveModel(ChatResponse response) {
        if (response.getMetadata() != null && StringUtils.hasText(response.getMetadata().getModel())) {
            return response.getMetadata().getModel();
        }
        return configuredModel;
    }

    private Integer promptTokens(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Integer tokens = response.getMetadata().getUsage().getPromptTokens();
        return tokens == null ? null : tokens;
    }

    private Integer outputTokens(ChatResponse response) {
        if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return null;
        }
        Integer tokens = response.getMetadata().getUsage().getCompletionTokens();
        return tokens == null ? null : tokens;
    }

    /** 按异常链里的关键字粗分类；provider 的错误码体系不稳定，不做精确映射。 */
    private LlmException.Kind classify(RuntimeException error) {
        String text = describeChain(error).toLowerCase();
        if (text.contains("429") || text.contains("rate limit") || text.contains("too many requests")) {
            return LlmException.Kind.RATE_LIMITED;
        }
        if (text.contains("timeout") || text.contains("timed out")
                || text.contains("connect") || text.contains("unreachable")) {
            return LlmException.Kind.UNAVAILABLE;
        }
        return LlmException.Kind.UNKNOWN;
    }

    private String describeChain(Throwable error) {
        StringBuilder text = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 5) {
            text.append(current.getClass().getSimpleName()).append(' ');
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
            depth++;
        }
        return text.toString();
    }

    private String friendlyMessage(LlmException.Kind kind) {
        return switch (kind) {
            case NOT_CONFIGURED -> "AI 服务未配置访问密钥";
            case RATE_LIMITED -> "AI 服务调用过于频繁，请稍后重试";
            case UNAVAILABLE -> "AI 服务暂时不可用，请稍后重试";
            case UNKNOWN -> "AI 服务调用失败，请稍后重试";
        };
    }
}
