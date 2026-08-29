package com.aetheris.aiagent.config;

import com.aetheris.aiagent.agent.DataTools;
import com.aetheris.aiagent.llm.LlmClient;
import com.aetheris.aiagent.llm.SpringAiLlmClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 装配 LLM 客户端与它可用的工具集。
 *
 * <p>显式装配而非在 {@code SpringAiLlmClient} 上加 {@code @Component}：后者的构造参数含
 * {@code List<Object>}，Spring 会尝试把容器里所有 Bean 注入进去，行为不可预期。
 * 在这里明确列出要暴露给模型的工具对象，新增工具类时只改这一处。
 */
@Configuration
public class AgentConfig {

    @Bean
    public LlmClient llmClient(
            ChatModel chatModel,
            DataTools dataTools,
            @Value("${spring.ai.deepseek.chat.options.model:deepseek-chat}") String configuredModel,
            @Value("${spring.ai.deepseek.api-key:}") String apiKey) {
        return new SpringAiLlmClient(chatModel, configuredModel, apiKey, List.of(dataTools));
    }
}
