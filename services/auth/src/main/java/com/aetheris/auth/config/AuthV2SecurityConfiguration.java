package com.aetheris.auth.config;

import com.aetheris.auth.security.AuthSessionValidator;
import com.aetheris.auth.rbac.RbacConstants;
import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

/** Explicit v2 installation. Missing files/keys are startup errors, with no legacy fallback. */
@Configuration(proxyBeanMethods = false)
public class AuthV2SecurityConfiguration {
    private static final Set<String> CALLERS = Set.of("gateway", "system", "metadata", "serving", "quality",
            "ingestion", "governance", "workflow", "ai-agent");
    private static final Map<String, String> OPERATIONS = Map.of(
            "/internal/v2/sessions/check", "auth.session.check",
            "/internal/v2/permissions/check", "auth.permission.check",
            "/internal/v2/permissions/visibility", "auth.permission.visibility",
            "/internal/v2/permissions/check-batch", "auth.permission.batch",
            "/internal/v2/permissions/may-manage", "auth.permission.manage",
            "/internal/v2/permissions/may-mutate", "auth.permission.mutate",
            "/internal/v2/permissions/version", "auth.permission.version");
    private static final Set<String> MANAGERS=Set.of("gateway","system","serving","workflow","quality","ingestion","governance");
    private static final Set<String> RESOURCE_CALLERS=Set.of("metadata","serving","workflow","quality","ingestion","governance");

    public static boolean resourceAllowed(String caller,String resource,String action) {
        if(RbacConstants.SERVICE_RESOURCES.getOrDefault(caller,List.of()).contains(resource)) return true;
        // Global audit visibility is a separate read capability, never implied by metric/table grants.
        if("metadata".equals(caller)&&"query_audit".equals(resource)&&"view".equals(action))return true;
        // API contracts are persisted by metadata; execution remains serving's separate operation.
        if("metadata".equals(caller)&&"data_service".equals(resource)
                &&Set.of("view","create","update","delete","publish","deprecate").contains(action))return true;
        // Definition approval may inspect these two capabilities of the original USER only.
        // Durable worker execution still requires metadata's separate source-bound assertion.
        if(Set.of("ingestion","workflow").contains(caller) && "datasource".equals(resource) && Set.of("query","write").contains(action)) return true;
        if("quality".equals(caller) && "datasource".equals(resource) && "query".equals(action)) return true;
        return "serving".equals(caller) && Set.of("metric","datasource","table").contains(resource) && "query".equals(action);
    }
    public static boolean mutationAllowed(String caller,String service) {
        return RbacConstants.SERVICE_RESOURCES.containsKey(service) && ("gateway".equals(caller) || caller.equals(service));
    }

    public static boolean isPublic(String method, String path) {
        return "POST".equals(method) && Set.of("/api/auth/login", "/api/auth/refresh", "/api/auth/logout").contains(path);
    }
    public static DirectRoutePolicy directRoutes() {
        return (method, path) -> isPublic(method, path) ? DirectRoutePolicy.Decision.PUBLIC
                : path.startsWith("/api/auth/") || path.equals("/api/auth")
                ? DirectRoutePolicy.Decision.PROTECTED : DirectRoutePolicy.Decision.REJECT;
    }
    public static RoutePolicy workloadRoutes() {
        return (method, path) -> {
            if ("POST".equals(method) && "/internal/v2/permissions/published-key-query-authorize".equals(path))
                return Optional.of(new RoutePolicy.Route("auth.published-key-query.authorize",Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN));
            if ("POST".equals(method) && "/internal/v2/permissions/fixed-execution-authorize".equals(path))
                return Optional.of(new RoutePolicy.Route("auth.fixed-execution.authorize",Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN));
            String operation = "POST".equals(method) ? OPERATIONS.get(path) : null;
            if (operation == null) return Optional.empty();
            boolean noUser = path.equals("/internal/v2/sessions/check") || path.equals("/internal/v2/permissions/version");
            Set<String> callers=path.endsWith("/may-manage") ? MANAGERS
                    : path.endsWith("/visibility") ? Set.of("metadata")
                    : path.endsWith("/check-batch") || path.equals("/internal/v2/permissions/check") ? RESOURCE_CALLERS : CALLERS;
            Map<String,Set<String>> relays=!noUser && callers.contains("metadata")
                    ? Map.of("metadata",Set.of("metadata","serving","ai-agent")) : Map.of();
            return Optional.of(new RoutePolicy.Route(operation, callers,
                    noUser ? RoutePolicy.UserMode.FORBIDDEN : RoutePolicy.UserMode.REQUIRED,relays));
        };
    }
    @Bean public ProtocolInstallation authProtocolInstallation(Environment environment) {
        String context = environment.getProperty("server.servlet.context-path", "");
        if (!context.isEmpty() && !"/".equals(context)) throw new IllegalStateException("Auth v2 requires root context");
        return ProtocolInstallation.load("auth", Path.of(required(environment, "registry-file")),
                required(environment, "workload-kid"), Path.of(required(environment, "workload-private-file")),
                null, null, Clock.systemUTC());
    }
    private static String required(Environment environment, String suffix) {
        String value = environment.getRequiredProperty("aetheris.security." + suffix);
        if (value.isBlank()) throw new IllegalStateException("Missing required security installation property");
        return value;
    }
    @Bean public PostgresNonceStore authNonceStore(DataSource dataSource) {
        return new PostgresNonceStore(dataSource, "auth", Clock.systemUTC());
    }
    @Bean(name="authSystemTransport",destroyMethod="close")
    public SignedHttpClient authSystemTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("auth",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("system",URI.create(environment.getRequiredProperty("aetheris.system.base-url"))),
                Map.of("system",Set.of("system.permission-event.record")),
                new SignedHttpClient.Limits(16*1024,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public FilterRegistrationBean<WorkloadProofFilter> authWorkloadProofFilter(
            ProtocolInstallation installation, PostgresNonceStore nonces, AuthSessionValidator sessions) {
        var filter = new WorkloadProofFilter("auth", installation.verifier(), installation.workloadSigner(),
                workloadRoutes(), nonces, sessions, Clock.systemUTC(), TimePolicy.defaults());
        var bean = new FilterRegistrationBean<>(filter);
        bean.setName("authWorkloadProofFilter"); bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.addUrlPatterns("/*"); bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        bean.setAsyncSupported(false); return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> authDirectUserProofFilter(
            ProtocolInstallation installation, AuthSessionValidator sessions) {
        var bean = new FilterRegistrationBean<>(new DirectUserProofFilter("auth", installation.verifier(), sessions, directRoutes()));
        bean.setName("authDirectUserProofFilter"); bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 21);
        bean.addUrlPatterns("/*"); bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        bean.setAsyncSupported(false); return bean;
    }
}
