package com.aetheris.mcp.config;

import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.DirectRoutePolicy;
import com.aetheris.security.servlet.DirectUserProofFilter;
import com.aetheris.security.servlet.SessionValidator;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** MCP 作为对外边界，只接受网关签发的 USER 证明，并为下游服务签署工作负载证明。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aetheris.security.registry-file")
public class McpV2SecurityConfiguration {

    public static final Set<String> METADATA_OPERATIONS = Set.of("metadata.agent.search", "metadata.agent.describe",
            "metadata.user.sql-query", "metadata.agent.metric-list", "metadata.user.metric-query");
    public static final Set<String> LEARNING_OPERATIONS = Set.of("learning.mcp.path", "learning.mcp.lab",
            "learning.mcp.submissions", "learning.mcp.publish-request", "learning.mcp.approval");

    @Bean
    public ProtocolInstallation mcpProtocolInstallation(Environment environment) {
        String context = environment.getProperty("server.servlet.context-path", "");
        if (!context.isEmpty() && !"/".equals(context)) {
            throw new IllegalStateException("mcp v2 requires root context");
        }
        return ProtocolInstallation.load("mcp", Path.of(required(environment, "registry-file")),
                required(environment, "workload-kid"), Path.of(required(environment, "workload-private-file")),
                null, null, Clock.systemUTC());
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty("aetheris.security." + suffix);
        if (value.isBlank()) throw new IllegalStateException("Missing mcp security installation property: " + suffix);
        return value;
    }

    @Bean(name = "mcpAuthTransport", destroyMethod = "close")
    public SignedHttpClient mcpAuthTransport(ProtocolInstallation installation, Environment environment) {
        return new SignedHttpClient("mcp", installation.workloadSigner(), installation.verifier(), Clock.systemUTC(),
                TimePolicy.defaults(),
                Map.of("auth", URI.create(environment.getRequiredProperty("aetheris.auth.base-url"))),
                Map.of("auth", Set.of("auth.session.check")),
                new SignedHttpClient.Limits(16_384, 8_192, 16, Duration.ofMillis(500), Duration.ofSeconds(2)));
    }

    @Bean(name = "mcpMetadataTransport", destroyMethod = "close")
    public SignedHttpClient mcpMetadataTransport(ProtocolInstallation installation, Environment environment) {
        return new SignedHttpClient("mcp", installation.workloadSigner(), installation.verifier(), Clock.systemUTC(),
                TimePolicy.defaults(),
                Map.of("metadata", URI.create(environment.getRequiredProperty("aetheris.metadata.base-url"))),
                Map.of("metadata", METADATA_OPERATIONS),
                new SignedHttpClient.Limits(1024 * 1024, 1024 * 1024, 16, Duration.ofMillis(500), Duration.ofSeconds(45)));
    }

    @Bean(name = "mcpLearningTransport", destroyMethod = "close")
    public SignedHttpClient mcpLearningTransport(ProtocolInstallation installation, Environment environment) {
        return new SignedHttpClient("mcp", installation.workloadSigner(), installation.verifier(), Clock.systemUTC(),
                TimePolicy.defaults(),
                Map.of("learning", URI.create(environment.getRequiredProperty("aetheris.learning.base-url"))),
                Map.of("learning", LEARNING_OPERATIONS),
                new SignedHttpClient.Limits(64 * 1024, 1024 * 1024, 16, Duration.ofMillis(500), Duration.ofSeconds(10)));
    }

    @Bean
    public AuthProtocolClient mcpAuthClient(@Qualifier("mcpAuthTransport") SignedHttpClient transport) {
        return new AuthProtocolClient(transport);
    }

    @Bean
    public SessionValidator mcpSessionValidator(AuthProtocolClient auth) {
        return (uid, username, version) -> {
            var pending = auth.sessionActive(uid, username, version);
            try {
                return pending.get(2500, TimeUnit.MILLISECONDS);
            } catch (Exception failure) {
                pending.cancel(true);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                throw failure;
            }
        };
    }

    @Bean
    public FilterRegistrationBean<DirectUserProofFilter> mcpDirectUserProofFilter(
            ProtocolInstallation installation, SessionValidator sessions) {
        var bean = new FilterRegistrationBean<>(new DirectUserProofFilter("mcp", installation.verifier(), sessions,
                (method, path) -> path.equals("/api/mcp") || path.startsWith("/api/mcp/")
                        ? DirectRoutePolicy.Decision.PROTECTED : DirectRoutePolicy.Decision.REJECT));
        bean.setName("mcpDirectUserProofFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 21);
        bean.addUrlPatterns("/*");
        bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        bean.setAsyncSupported(false);
        return bean;
    }
}
