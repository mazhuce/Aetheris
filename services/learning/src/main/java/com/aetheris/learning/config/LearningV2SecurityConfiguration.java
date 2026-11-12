package com.aetheris.learning.config;

import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 学习服务的 v2 身份边界。只有配置了密钥安装时才启用；未配置时服务不会接受 /api 请求，
 * 除非显式开启仅用于本地演示的 dev-mode。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "aetheris.security.registry-file")
public class LearningV2SecurityConfiguration {

    @Bean
    public ProtocolInstallation learningProtocolInstallation(Environment environment) {
        String context = environment.getProperty("server.servlet.context-path", "");
        if (!context.isEmpty() && !"/".equals(context)) {
            throw new IllegalStateException("learning v2 requires root context");
        }
        return ProtocolInstallation.load("learning", Path.of(required(environment, "registry-file")),
                required(environment, "workload-kid"), Path.of(required(environment, "workload-private-file")),
                null, null, Clock.systemUTC());
    }

    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty("aetheris.security." + suffix);
        if (value.isBlank()) throw new IllegalStateException("Missing learning security installation property: " + suffix);
        return value;
    }

    @Bean(name = "learningAuthTransport", destroyMethod = "close")
    public SignedHttpClient learningAuthTransport(ProtocolInstallation installation, Environment environment) {
        return new SignedHttpClient("learning", installation.workloadSigner(), installation.verifier(), Clock.systemUTC(),
                TimePolicy.defaults(),
                java.util.Map.of("auth", URI.create(environment.getRequiredProperty("aetheris.auth.base-url"))),
                java.util.Map.of("auth", Set.of("auth.session.check")),
                new SignedHttpClient.Limits(16_384, 8_192, 16, Duration.ofMillis(500), Duration.ofSeconds(2)));
    }

    @Bean
    public AuthProtocolClient learningAuthClient(@Qualifier("learningAuthTransport") SignedHttpClient transport) {
        return new AuthProtocolClient(transport);
    }

    @Bean
    public SessionValidator learningSessionValidator(AuthProtocolClient auth) {
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
    public PostgresNonceStore learningNonceStore(DataSource dataSource) {
        return new PostgresNonceStore(dataSource, "learning", Clock.systemUTC());
    }

    public static RoutePolicy workloadRoutes() {
        return (method, path) -> {
            if ("GET".equals(method) && path.matches("/internal/v2/mcp/approvals/[0-9]{1,19}")) {
                return user("learning.mcp.approval");
            }
            if (!"POST".equals(method)) return Optional.empty();
            return switch (path) {
                case "/internal/v2/mcp/learning-path" -> user("learning.mcp.path");
                case "/internal/v2/mcp/lab" -> user("learning.mcp.lab");
                case "/internal/v2/mcp/submissions" -> user("learning.mcp.submissions");
                case "/internal/v2/mcp/lab-publish-request" -> user("learning.mcp.publish-request");
                default -> Optional.empty();
            };
        };
    }

    private static Optional<RoutePolicy.Route> user(String operation) {
        return Optional.of(new RoutePolicy.Route(operation, Set.of("mcp"), RoutePolicy.UserMode.REQUIRED));
    }

    @Bean
    public FilterRegistrationBean<WorkloadProofFilter> learningWorkloadProofFilter(
            ProtocolInstallation installation, PostgresNonceStore nonces, SessionValidator sessions) {
        var filter = new WorkloadProofFilter("learning", installation.verifier(), installation.workloadSigner(),
                workloadRoutes(), nonces, sessions, Clock.systemUTC(), TimePolicy.defaults());
        var bean = new FilterRegistrationBean<>(filter);
        bean.setName("learningWorkloadProofFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 19);
        bean.addUrlPatterns("/*");
        bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        bean.setAsyncSupported(false);
        return bean;
    }

    @Bean
    public FilterRegistrationBean<DirectUserProofFilter> learningDirectUserProofFilter(
            ProtocolInstallation installation, SessionValidator sessions) {
        var bean = new FilterRegistrationBean<>(new DirectUserProofFilter("learning", installation.verifier(),
                sessions, (method, path) -> path.equals("/api/learning") || path.startsWith("/api/learning/")
                        ? DirectRoutePolicy.Decision.PROTECTED : DirectRoutePolicy.Decision.REJECT));
        bean.setName("learningDirectUserProofFilter");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 21);
        bean.addUrlPatterns("/*");
        bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        bean.setAsyncSupported(false);
        return bean;
    }
}
