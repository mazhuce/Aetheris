package com.aetheris.system.config;

import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
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
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** File-backed v2 only. Runtime DB privileges and nonce migration are deployment prerequisites. */
@Configuration(proxyBeanMethods=false)
public class SystemV2SecurityConfiguration {
    public static RoutePolicy workloadRoutes() {
        return (method,path)-> {
            if(!"POST".equals(method))return Optional.empty();
            return switch(path) {
                case "/internal/v2/audit/logs"->Optional.of(new RoutePolicy.Route("system.audit.record",Set.of("gateway"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/audit/permission-events"->Optional.of(new RoutePolicy.Route("system.permission-event.record",Set.of("auth"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/notifications"->Optional.of(new RoutePolicy.Route("system.notification.record",Set.of("workflow","quality","ingestion","metadata"),RoutePolicy.UserMode.FORBIDDEN));
                default->Optional.empty();
            };
        };
    }
    public static DirectRoutePolicy directRoutes() {
        return (method,path)->path.equals("/api/system")||path.startsWith("/api/system/")
                ?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT;
    }
    @Bean public ProtocolInstallation systemProtocolInstallation(Environment environment) {
        String context=environment.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!context.equals("/"))throw new IllegalStateException("System v2 requires root context");
        return ProtocolInstallation.load("system",Path.of(required(environment,"registry-file")),required(environment,"workload-kid"),
                Path.of(required(environment,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment env,String suffix) {
        String value=env.getRequiredProperty("aetheris.security."+suffix);
        if(value.isBlank())throw new IllegalStateException("Missing required system security installation property");
        return value;
    }
    @Bean(destroyMethod="close") public SignedHttpClient systemAuthTransport(ProtocolInstallation installation,Environment env) {
        return new SignedHttpClient("system",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("auth",URI.create(env.getProperty("aetheris.auth.base-url","http://localhost:8087"))),
                Map.of("auth",Set.of("auth.session.check","auth.permission.manage")),
                new SignedHttpClient.Limits(16_384,8192,32,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public AuthProtocolClient systemAuthProtocolClient(SignedHttpClient systemAuthTransport) {return new AuthProtocolClient(systemAuthTransport);}
    @Bean public SessionValidator systemSessionValidator(AuthProtocolClient auth) {
        return (uid,name,av)-> {
            var pending=auth.sessionActive(uid,name,av);
            try{return pending.get(2500,TimeUnit.MILLISECONDS);}
            catch(Exception failure){pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw failure;}
        };
    }
    @Bean public PostgresNonceStore systemNonceStore(DataSource dataSource) {return new PostgresNonceStore(dataSource,"system",Clock.systemUTC());}
    @Bean public FilterRegistrationBean<WorkloadProofFilter> systemWorkloadProofFilter(ProtocolInstallation installation,PostgresNonceStore nonces,SessionValidator sessions) {
        var filter=new WorkloadProofFilter("system",installation.verifier(),installation.workloadSigner(),workloadRoutes(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults());
        var bean=new FilterRegistrationBean<>(filter);bean.setName("systemWorkloadProofFilter");bean.setOrder(Ordered.HIGHEST_PRECEDENCE+20);
        bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> systemDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions) {
        var bean=new FilterRegistrationBean<>(new DirectUserProofFilter("system",installation.verifier(),sessions,directRoutes()));
        bean.setName("systemDirectUserProofFilter");bean.setOrder(Ordered.HIGHEST_PRECEDENCE+21);
        bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);return bean;
    }
}
