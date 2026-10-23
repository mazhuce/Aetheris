package com.aetheris.ingestion.config;

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
public class IngestionV2SecurityConfiguration {
    private static final Set<String> AUTH_OPERATIONS=Set.of("auth.session.check","auth.permission.check",
            "auth.permission.batch","auth.permission.manage","auth.permission.mutate");
    @Bean public ProtocolInstallation ingestionProtocolInstallation(Environment environment) {
        String context=environment.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!"/".equals(context))throw new IllegalStateException("Ingestion v2 requires root context");
        return ProtocolInstallation.load("ingestion",Path.of(required(environment,"registry-file")),required(environment,"workload-kid"),
                Path.of(required(environment,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment environment,String suffix) {
        String value=environment.getRequiredProperty("aetheris.security."+suffix);
        if(value.isBlank())throw new IllegalStateException("Missing required security installation property");
        return value;
    }
    @Bean(name="ingestionAuthTransport",destroyMethod="close")
    public SignedHttpClient ingestionAuthTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("ingestion",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("auth",URI.create(environment.getRequiredProperty("aetheris.auth.base-url"))),Map.of("auth",AUTH_OPERATIONS),
                new SignedHttpClient.Limits(256*1024,256*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="ingestionSystemTransport",destroyMethod="close")
    public SignedHttpClient ingestionSystemTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("ingestion",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("system",URI.create(environment.getRequiredProperty("aetheris.system.base-url"))),
                Map.of("system",Set.of("system.notification.record")),
                new SignedHttpClient.Limits(16*1024,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(name="ingestionMetadataTransport",destroyMethod="close")
    public SignedHttpClient ingestionMetadataTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("ingestion",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("metadata",URI.create(environment.getRequiredProperty("aetheris.metadata.base-url"))),
                Map.of("metadata",Set.of("metadata.fixed-transfer.submit","metadata.fixed-execution.status")),
                new SignedHttpClient.Limits(64*1024,16*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public AuthProtocolClient ingestionAuthClient(@Qualifier("ingestionAuthTransport") SignedHttpClient transport) {
        return new AuthProtocolClient(transport);
    }
    @Bean public SessionValidator ingestionSessionValidator(AuthProtocolClient auth) {
        // No USER proof here: checking the raw tuple must not recurse through user authentication.
        return (uid,username,version)->{
            var pending=auth.sessionActive(uid,username,version);
            try{return pending.get(2500,java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw failure;}
        };
    }
    @Bean public PostgresNonceStore ingestionNonceStore(DataSource dataSource) {
        return new PostgresNonceStore(dataSource,"ingestion",Clock.systemUTC());
    }
    public static DirectRoutePolicy directRoutes() {
        return (method,path)->path.equals("/api/ingestion")||path.startsWith("/api/ingestion/")
                ?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT;
    }
    public static RoutePolicy workloadRoutes(){return (method,path)->"POST".equals(method)&&"/internal/v2/executions/lookup".equals(path)
            ?Optional.of(new RoutePolicy.Route("ingestion.execution.lookup",Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN)):Optional.empty();}
    @Bean public FilterRegistrationBean<WorkloadProofFilter> ingestionWorkloadProofFilter(ProtocolInstallation installation,
            PostgresNonceStore nonces,SessionValidator sessions) {
        var filter=new WorkloadProofFilter("ingestion",installation.verifier(),installation.workloadSigner(),
                workloadRoutes(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults());
        var bean=new FilterRegistrationBean<>(filter);bean.setName("ingestionWorkloadProofFilter");
        configure(bean,Ordered.HIGHEST_PRECEDENCE+20);return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> ingestionDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions) {
        var bean=new FilterRegistrationBean<>(new DirectUserProofFilter("ingestion",installation.verifier(),sessions,directRoutes()));
        bean.setName("ingestionDirectUserProofFilter");configure(bean,Ordered.HIGHEST_PRECEDENCE+21);return bean;
    }
    private static void configure(FilterRegistrationBean<?> bean,int order) {
        bean.setOrder(order);bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);
    }
}
