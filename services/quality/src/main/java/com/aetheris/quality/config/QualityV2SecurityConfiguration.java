package com.aetheris.quality.config;

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
public class QualityV2SecurityConfiguration {
    private static final Set<String> AUTH_OPERATIONS=Set.of("auth.session.check","auth.permission.check",
            "auth.permission.batch","auth.permission.manage","auth.permission.mutate");
    @Bean public ProtocolInstallation qualityProtocolInstallation(Environment environment) {
        String context=environment.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!"/".equals(context))throw new IllegalStateException("Quality v2 requires root context");
        return ProtocolInstallation.load("quality",Path.of(required(environment,"registry-file")),required(environment,"workload-kid"),
                Path.of(required(environment,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment environment,String suffix) {
        String value=environment.getRequiredProperty("aetheris.security."+suffix);
        if(value.isBlank())throw new IllegalStateException("Missing required security installation property");
        return value;
    }
    @Bean(name="qualityAuthTransport",destroyMethod="close")
    public SignedHttpClient qualityAuthTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("quality",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("auth",URI.create(environment.getRequiredProperty("aetheris.auth.base-url"))),Map.of("auth",AUTH_OPERATIONS),
                new SignedHttpClient.Limits(256*1024,256*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="qualitySystemTransport",destroyMethod="close")
    public SignedHttpClient qualitySystemTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("quality",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("system",URI.create(environment.getRequiredProperty("aetheris.system.base-url"))),Map.of("system",Set.of("system.notification.record")),
                new SignedHttpClient.Limits(16*1024,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="qualityMetadataTransport",destroyMethod="close")
    public SignedHttpClient qualityMetadataTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("quality",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("metadata",URI.create(environment.getRequiredProperty("aetheris.metadata.base-url"))),
                Map.of("metadata",Set.of("metadata.fixed-quality-query.submit","metadata.fixed-execution.status")),
                new SignedHttpClient.Limits(64*1024,16*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="qualityWorkflowTransport",destroyMethod="close")
    public SignedHttpClient qualityWorkflowTransport(ProtocolInstallation installation,Environment env){
        return new SignedHttpClient("quality",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
            Map.of("workflow",URI.create(env.getProperty("aetheris.workflow.base-url","http://127.0.0.1:8088"))),
            Map.of("workflow",Set.of("workflow.quality-parent.lookup")),new SignedHttpClient.Limits(65536,1024*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    public static RoutePolicy workloadRoutes(){return (method,path)->{
        if(!"POST".equals(method))return Optional.empty();
        if("/internal/v2/executions/lookup".equals(path))return Optional.of(new RoutePolicy.Route("quality.execution.lookup",Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN));
        String op=switch(path){case "/internal/v2/workflow-children/submit"->"quality.workflow-child.submit";case "/internal/v2/workflow-children/status"->"quality.workflow-child.status";case "/internal/v2/workflow-children/definition"->"quality.workflow-child.definition";default->null;};
        return op==null?Optional.empty():Optional.of(new RoutePolicy.Route(op,Set.of("workflow"),RoutePolicy.UserMode.FORBIDDEN));
    };}
    @Bean public AuthProtocolClient qualityAuthClient(@Qualifier("qualityAuthTransport") SignedHttpClient transport) {
        return new AuthProtocolClient(transport);
    }
    @Bean public SessionValidator qualitySessionValidator(AuthProtocolClient auth) {
        // No USER proof here: checking the raw tuple must not recurse through user authentication.
        return (uid,username,version)->{
            var pending=auth.sessionActive(uid,username,version);
            try{return pending.get(2500,java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw failure;}
        };
    }
    @Bean public PostgresNonceStore qualityNonceStore(DataSource dataSource) {
        return new PostgresNonceStore(dataSource,"quality",Clock.systemUTC());
    }
    public static DirectRoutePolicy directRoutes() {
        return (method,path)->path.equals("/api/quality")||path.startsWith("/api/quality/")
                ?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT;
    }
    @Bean public FilterRegistrationBean<WorkloadProofFilter> qualityWorkloadProofFilter(ProtocolInstallation installation,
            PostgresNonceStore nonces,SessionValidator sessions) {
        var filter=new WorkloadProofFilter("quality",installation.verifier(),installation.workloadSigner(),
                workloadRoutes(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults());
        var bean=new FilterRegistrationBean<>(filter);bean.setName("qualityWorkloadProofFilter");
        configure(bean,Ordered.HIGHEST_PRECEDENCE+20);return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> qualityDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions) {
        var bean=new FilterRegistrationBean<>(new DirectUserProofFilter("quality",installation.verifier(),sessions,directRoutes()));
        bean.setName("qualityDirectUserProofFilter");configure(bean,Ordered.HIGHEST_PRECEDENCE+21);return bean;
    }
    private static void configure(FilterRegistrationBean<?> bean,int order) {
        bean.setOrder(order);bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);
    }
}
