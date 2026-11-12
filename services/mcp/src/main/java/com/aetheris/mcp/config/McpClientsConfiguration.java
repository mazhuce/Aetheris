package com.aetheris.mcp.config;

import com.aetheris.mcp.client.DevLearningReadClient;
import com.aetheris.mcp.client.LearningReadClient;
import com.aetheris.mcp.client.PlatformReadClient;
import com.aetheris.mcp.client.SignedLearningReadClient;
import com.aetheris.mcp.client.SignedPlatformReadClient;
import com.aetheris.mcp.client.UnavailablePlatformReadClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 后端客户端装配：装了 v2 密钥走签名转发；只开 dev-mode 时平台工具显式不可用。
 * 两者都不满足时容器启动失败，避免“静默无鉴权”。
 */
@Configuration(proxyBeanMethods = false)
public class McpClientsConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "aetheris.security.registry-file")
    public PlatformReadClient signedPlatformReadClient(
            @Qualifier("mcpMetadataTransport") SignedHttpClient metadataTransport) {
        return new SignedPlatformReadClient(metadataTransport);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "aetheris.security.registry-file")
    public LearningReadClient signedLearningReadClient(
            @Qualifier("mcpLearningTransport") SignedHttpClient learningTransport) {
        return new SignedLearningReadClient(learningTransport);
    }

    @Bean
    @ConditionalOnProperty(name = "aetheris.mcp.dev-mode", havingValue = "true")
    public LearningReadClient devLearningReadClient(
            @Value("${aetheris.learning.base-url:http://localhost:8090}") String baseUrl, McpProperties properties) {
        return new DevLearningReadClient(baseUrl, properties);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(PlatformReadClient.class)
    public PlatformReadClient platformReadClientFallback() {
        return new UnavailablePlatformReadClient();
    }
}
