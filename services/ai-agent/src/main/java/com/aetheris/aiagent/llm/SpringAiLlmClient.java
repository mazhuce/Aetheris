package com.aetheris.aiagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
 * HTTP 代码：它带来了统一的重试、可观测性埋点，以及 {@code spring-ai-autoconfigure-model-tool}
 * 提供的工具调用能力 —— 后者是后续做 DataAgent（让模型自己查元数据、执行 SQL）的基础。
 *
 * <p>超时与重试由 Spring AI 的自动配置负责，在 application.yml 中声明，不再散落在代码里。
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

    @Override
    public LlmReply chat(List<LlmMessage> messages) {
        requireApiKey();

        try {
            ChatResponse response = chatModel.call(new Prompt(toSpringAiMessages(messages)));
            String content = response.getResult() == null
                    ? null
                    : response.getResult().getOutput().getText();
            if (!StringUtils.hasText(content)) {
                throw new LlmException(LlmException.Kind.UNKNOWN, "AI 未返回有效回复", null);
            }
            return new LlmReply(
                    content,
                    resolveModel(response),
                    promptTokens(response),
                    outputTokens(response));
        } catch (LlmException error) {
            throw error;
        } catch (RuntimeException error) {
            // 原始异常可能含请求内容，只记日志，不外泄给调用方。
            log.warn("DeepSeek chat call failed", error);
            throw new LlmException(classify(error), friendlyMessage(classify(error)), error);
        }
    }

    @Override
    public LlmReply chatWithTools(List<LlmMessage> messages, String systemPrompt) {
        requireApiKey();

        try {
            // 用 ChatClient 而非裸 ChatModel：工具调用循环（模型请求调用 → 执行 → 回填结果
            // → 再次询问模型）由它内建驱动，不需要自己实现，也避免漏掉边界情况。
            var request = ChatClient.create(chatModel)
                    .prompt()
                    .system(systemPrompt)
                    .messages(toSpringAiMessages(messages));

            if (!toolObjects.isEmpty()) {
                request = request.tools(toolObjects.toArray());
            }

            ChatResponse response = request.call().chatResponse();
            if (response == null) {
                throw new LlmException(LlmException.Kind.UNKNOWN, "AI 未返回有效回复", null);
            }
            String content = response.getResult() == null
                    ? null
                    : response.getResult().getOutput().getText();
            if (!StringUtils.hasText(content)) {
                // 模型只调了工具却没给结论时会走到这里，提示比空白更有用。
                throw new LlmException(
                        LlmException.Kind.UNKNOWN,
                        "AI 未给出最终回答，请重新提问或把问题拆得更具体",
                        null);
            }
            return new LlmReply(
                    content,
                    resolveModel(response),
                    promptTokens(response),
                    outputTokens(response));
        } catch (LlmException error) {
            throw error;
        } catch (RuntimeException error) {
            log.warn("DeepSeek tool-calling chat failed", error);
            throw new LlmException(classify(error), friendlyMessage(classify(error)), error);
        }
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
