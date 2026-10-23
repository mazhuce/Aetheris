package com.aetheris.aiagent.llm;
import com.aetheris.aiagent.agent.DataTools;
import com.aetheris.aiagent.config.AgentConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.*;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.chat.prompt.Prompt;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class SpringAiWireBudgetTest {
    @Test void realSpringAiOnePointZeroDoesNotHideTheToolLoop() throws Exception {
        var requests=new AtomicInteger();var ping=new AiSafetyEvaluationTest.Ping();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            var body=new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody());
            assertTrue(body.path("max_tokens").asInt()<=1024);
            int round=requests.incrementAndGet();
            String message=round==1?
                    "\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]":
                    "\"content\":\"safe final answer\"";
            String json="{\"id\":\"offline\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"fake\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","+message+"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var api=DeepSeekApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort())
                    .completionsPath("/chat/completions").apiKey("offline-test").build();
            var model=spy(DeepSeekChatModel.builder().deepSeekApi(api).defaultOptions(DeepSeekChatOptions.builder().model("deepseek-chat").build()).build());
            var client=new SpringAiLlmClient(model,"fake","offline-test",List.of(ping));
            assertEquals("safe final answer",client.chatWithTools(List.of(LlmMessage.user("question")),"trusted").content());
            assertEquals(2,requests.get());assertEquals(1,ping.calls.get());
            verify(model,times(2)).call(any(Prompt.class));
        } finally {server.stop(0);}
    }
    @Test void productionConfigurationDisablesHiddenHttpRetries() throws Exception {
        var requests=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] body="{\"error\":{\"message\":\"offline error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(500,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            var api=DeepSeekApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).completionsPath("/chat/completions").apiKey("offline-test").build();
            var model=DeepSeekChatModel.builder().deepSeekApi(api).build();
            var client=new AgentConfig().llmClient(model,mock(DataTools.class),AiBudgetLimits.defaults(),
                    java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO,"fake","offline-test");
            assertThrows(LlmException.class,()->client.chat(List.of(LlmMessage.user("question"))));
            assertEquals(1,requests.get());
        } finally {server.stop(0);}
    }
}
