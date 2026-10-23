package com.aetheris.governance.config;

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
public class GovernanceV2SecurityConfiguration {
    private static final Set<String> AUTH_OPERATIONS=Set.of("auth.session.check","auth.permission.check",
            "auth.permission.batch","auth.permission.manage","auth.permission.mutate");
    @Bean public ProtocolInstallation governanceProtocolInstallation(Environment environment) {
        String context=environment.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!"/".equals(context))throw new IllegalStateException("Governance v2 requires root context");
        return ProtocolInstallation.load("governance",Path.of(required(environment,"registry-file")),required(environment,"workload-kid"),
                Path.of(required(environment,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment environment,String suffix) {
        String value=environment.getRequiredProperty("aetheris.security."+suffix);
        if(value.isBlank())throw new IllegalStateException("Missing required security installation property");
        return value;
    }
    @Bean(name="governanceAuthTransport",destroyMethod="close")
    public SignedHttpClient governanceAuthTransport(ProtocolInstallation installation,Environment environment) {
        return new SignedHttpClient("governance",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("auth",URI.create(environment.getRequiredProperty("aetheris.auth.base-url"))),Map.of("auth",AUTH_OPERATIONS),
                new SignedHttpClient.Limits(256*1024,256*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public AuthProtocolClient governanceAuthClient(@Qualifier("governanceAuthTransport") SignedHttpClient transport) {
        return new AuthProtocolClient(transport);
    }
    @Bean public SessionValidator governanceSessionValidator(AuthProtocolClient auth) {
        // No USER proof here: checking the raw tuple must not recurse through user authentication.
        return (uid,username,version)->auth.sessionActive(uid,username,version).get();
    }
    @Bean public PostgresNonceStore governanceNonceStore(DataSource dataSource) {
        return new PostgresNonceStore(dataSource,"governance",Clock.systemUTC());
    }
    public static DirectRoutePolicy directRoutes() {
        return (method,path)->path.equals("/api/governance")||path.startsWith("/api/governance/")
                ?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT;
    }
    @Bean public FilterRegistrationBean<WorkloadProofFilter> governanceWorkloadProofFilter(ProtocolInstallation installation,
            PostgresNonceStore nonces,SessionValidator sessions) {
        var filter=new WorkloadProofFilter("governance",installation.verifier(),installation.workloadSigner(),
                (method,path)->Optional.empty(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults());
        var bean=new FilterRegistrationBean<>(filter);bean.setName("governanceWorkloadProofFilter");
        configure(bean,Ordered.HIGHEST_PRECEDENCE+20);return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> governanceDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions) {
        var bean=new FilterRegistrationBean<>(new DirectUserProofFilter("governance",installation.verifier(),sessions,directRoutes()));
        bean.setName("governanceDirectUserProofFilter");configure(bean,Ordered.HIGHEST_PRECEDENCE+21);return bean;
    }
    private static void configure(FilterRegistrationBean<?> bean,int order) {
        bean.setOrder(order);bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);
    }
}
