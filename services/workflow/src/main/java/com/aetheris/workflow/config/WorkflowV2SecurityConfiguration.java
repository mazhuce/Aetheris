package com.aetheris.workflow.config;

import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Explicit asymmetric installation; no shared-token or configuration-switch fallback. */
@Configuration(proxyBeanMethods=false)
public class WorkflowV2SecurityConfiguration {
    private static final Set<String> AUTH_OPERATIONS=Set.of("auth.session.check","auth.permission.check",
            "auth.permission.batch","auth.permission.manage","auth.permission.mutate","auth.engine-execution.authorize");
    @Bean public ProtocolInstallation workflowProtocolInstallation(Environment environment) {
        String context=environment.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!"/".equals(context))throw new IllegalStateException("Workflow v2 requires root context");
        return ProtocolInstallation.load("workflow",Path.of(required(environment,"registry-file")),required(environment,"workload-kid"),
                Path.of(required(environment,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment environment,String suffix) {
        String value=environment.getRequiredProperty("aetheris.security."+suffix);
        if(value.isBlank())throw new IllegalStateException("Missing required security installation property");
        return value;
    }
    @Bean(name="workflowAuthTransport",destroyMethod="close")
    public SignedHttpClient workflowAuthTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("workflow",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("auth",URI.create(environment.getRequiredProperty("aetheris.auth.base-url"))),Map.of("auth",AUTH_OPERATIONS),
                new SignedHttpClient.Limits(256*1024,256*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="workflowSystemTransport",destroyMethod="close")
    public SignedHttpClient workflowSystemTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("workflow",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("system",URI.create(environment.getRequiredProperty("aetheris.system.base-url"))),Map.of("system",Set.of("system.notification.record")),
                new SignedHttpClient.Limits(16*1024,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="workflowMetadataTransport",destroyMethod="close")
    public SignedHttpClient workflowMetadataTransport(ProtocolInstallation installation,Environment environment){
        return new SignedHttpClient("workflow",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
            Map.of("metadata",URI.create(environment.getRequiredProperty("aetheris.metadata.base-url"))),
            Map.of("metadata",Set.of("metadata.fixed-sql.submit","metadata.fixed-execution.status","metadata.fixed-sql.validate-write")),
            new SignedHttpClient.Limits(1024*1024,16*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public AuthProtocolClient workflowAuthClient(@Qualifier("workflowAuthTransport") SignedHttpClient transport) {
        return new AuthProtocolClient(transport);
    }
    @Bean(name="workflowQualityTransport",destroyMethod="close")
    public SignedHttpClient workflowQualityTransport(ProtocolInstallation installation,Environment env){
        return new SignedHttpClient("workflow",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
            Map.of("quality",URI.create(env.getProperty("aetheris.quality.base-url","http://127.0.0.1:8083"))),
            Map.of("quality",Set.of("quality.workflow-child.submit","quality.workflow-child.status","quality.workflow-child.definition")),
            new SignedHttpClient.Limits(65536,65536,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public SessionValidator workflowSessionValidator(AuthProtocolClient auth) {
        // No USER proof here: checking the raw tuple must not recurse through user authentication.
        return (uid,username,version)->{
            var pending=auth.sessionActive(uid,username,version);
            try{return pending.get(2500,java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw failure;}
        };
    }
    @Bean public PostgresNonceStore workflowNonceStore(DataSource dataSource) {
        return new PostgresNonceStore(dataSource,"workflow",Clock.systemUTC());
    }
    public static DirectRoutePolicy directRoutes() {
        return (method,path)->path.equals("/api/workflow")||path.startsWith("/api/workflow/")||path.equals("/api/tasks")||path.startsWith("/api/tasks/")
                ?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT;
    }
    public static RoutePolicy workloadRoutes(){return (method,path)->{
        if(!"POST".equals(method))return Optional.empty();
        if("/internal/v2/executions/lookup".equals(path))return Optional.of(new RoutePolicy.Route("workflow.execution.lookup",Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN));
        if("/internal/v2/quality-parent/lookup".equals(path))return Optional.of(new RoutePolicy.Route("workflow.quality-parent.lookup",Set.of("metadata","quality"),RoutePolicy.UserMode.FORBIDDEN));
        return Optional.empty();
    };}
    @Bean public FilterRegistrationBean<WorkloadProofFilter> workflowWorkloadProofFilter(ProtocolInstallation installation,
            PostgresNonceStore nonces,SessionValidator sessions) {
        var filter=new WorkloadProofFilter("workflow",installation.verifier(),installation.workloadSigner(),
                workloadRoutes(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults());
        var bean=new FilterRegistrationBean<>(filter);bean.setName("workflowWorkloadProofFilter");
        configure(bean,Ordered.HIGHEST_PRECEDENCE+20);return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> workflowDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions) {
        var bean=new FilterRegistrationBean<>(new DirectUserProofFilter("workflow",installation.verifier(),sessions,directRoutes()));
        bean.setName("workflowDirectUserProofFilter");configure(bean,Ordered.HIGHEST_PRECEDENCE+21);return bean;
    }
    private static void configure(FilterRegistrationBean<?> bean,int order) {
        bean.setOrder(order);bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);
    }
}
