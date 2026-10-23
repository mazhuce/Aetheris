package com.aetheris.aiagent.config;
import com.aetheris.aiagent.llm.AiBudgetLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
@Configuration
public class AiBudgetConfig {
    @Bean public AiBudgetLimits aiBudgetLimits(
            @Value("${aetheris.ai.budget.model-calls:6}") int models,
            @Value("${aetheris.ai.budget.tool-calls:10}") int tools,
            @Value("${aetheris.ai.budget.input-tokens:128000}") int input,
            @Value("${aetheris.ai.budget.output-tokens:4096}") int output,
            @Value("${aetheris.ai.budget.tool-result-chars:12000}") int result,
            @Value("${aetheris.ai.budget.timeout-ms:60000}") long timeout,
            @Value("${aetheris.ai.budget.workers:4}") int workers,
            @Value("${aetheris.ai.budget.queue-capacity:8}") int queue) {
        return new AiBudgetLimits(models,tools,input,output,result,timeout,workers,queue);
    }
    @Bean public org.springframework.boot.web.client.RestClientCustomizer boundedAiHttpClient() {
        return builder->{
            var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1000);factory.setReadTimeout(15000);
            builder.requestFactory(factory);
        };
    }
}
