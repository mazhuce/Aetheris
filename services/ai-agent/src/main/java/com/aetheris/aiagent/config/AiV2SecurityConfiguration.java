package com.aetheris.aiagent.config;

import com.aetheris.aiagent.security.AiAsyncAdmissionFilter;
import com.aetheris.security.protocol.*;
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

@Configuration(proxyBeanMethods=false)
public class AiV2SecurityConfiguration {
    public static final Set<String> METADATA_OPERATIONS=Set.of("metadata.agent.search","metadata.agent.describe","metadata.user.sql-query","metadata.agent.metric-list","metadata.user.metric-query");
    @Bean public ProtocolInstallation aiProtocolInstallation(Environment environment){
        String context=environment.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!context.equals("/"))throw new IllegalStateException("AI v2 requires root context");
        return ProtocolInstallation.load("ai-agent",Path.of(required(environment,"registry-file")),required(environment,"workload-kid"),Path.of(required(environment,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment environment,String suffix){String value=environment.getRequiredProperty("aetheris.security."+suffix);if(value.isBlank())throw new IllegalStateException("Missing AI security installation");return value;}
    private SignedHttpClient transport(ProtocolInstallation installation,Environment environment,String audience,Set<String> operations,int bytes,Duration timeout){
        return new SignedHttpClient("ai-agent",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of(audience,URI.create(environment.getRequiredProperty("aetheris."+audience+".base-url"))),Map.of(audience,operations),
                new SignedHttpClient.Limits(bytes,bytes,16,Duration.ofMillis(500),timeout));
    }
    @Bean(name="aiMetadataTransport",destroyMethod="close") public SignedHttpClient metadata(ProtocolInstallation installation,Environment environment){return transport(installation,environment,"metadata",METADATA_OPERATIONS,1024*1024,Duration.ofSeconds(45));}
    @Bean(name="aiAuthTransport",destroyMethod="close") public SignedHttpClient auth(ProtocolInstallation installation,Environment environment){return transport(installation,environment,"auth",Set.of("auth.session.check"),16384,Duration.ofSeconds(2));}
    @Bean public SessionValidator aiSessionValidator(@Qualifier("aiAuthTransport") SignedHttpClient transport){var auth=new AuthProtocolClient(transport);return (uid,name,av)->auth.sessionActive(uid,name,av).get();}
    @Bean public PostgresNonceStore aiNonceStore(DataSource source){return new PostgresNonceStore(source,"ai",Clock.systemUTC());}
    @Bean public FilterRegistrationBean<WorkloadProofFilter> aiWorkloadProofFilter(ProtocolInstallation installation,PostgresNonceStore nonces,SessionValidator sessions){
        var bean=new FilterRegistrationBean<>(new WorkloadProofFilter("ai-agent",installation.verifier(),installation.workloadSigner(),(m,p)->Optional.empty(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults()));
        setup(bean,"aiWorkloadProofFilter",20,EnumSet.allOf(DispatcherType.class));return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> aiDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions){
        var bean=new FilterRegistrationBean<>(new DirectUserProofFilter("ai-agent",installation.verifier(),sessions,(m,p)->p.equals("/api/ai")||p.startsWith("/api/ai/")?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT));
        setup(bean,"aiDirectUserProofFilter",21,EnumSet.of(DispatcherType.REQUEST,DispatcherType.FORWARD,DispatcherType.INCLUDE,DispatcherType.ERROR));return bean;
    }
    @Bean public FilterRegistrationBean<AiAsyncAdmissionFilter> aiAsyncAdmissionFilter(ProtocolInstallation installation,SessionValidator sessions){
        var bean=new FilterRegistrationBean<>(new AiAsyncAdmissionFilter(installation.verifier(),sessions,Clock.systemUTC()));setup(bean,"aiAsyncAdmissionFilter",22,EnumSet.allOf(DispatcherType.class));return bean;
    }
    private static void setup(FilterRegistrationBean<?> bean,String name,int offset,EnumSet<DispatcherType> dispatchers){
        bean.setName(name);bean.setOrder(Ordered.HIGHEST_PRECEDENCE+offset);bean.addUrlPatterns("/*");bean.setDispatcherTypes(dispatchers);
        // Internal WorkloadProofFilter still forbids startAsync on its own wrapper. API chat needs Servlet async.
        bean.setAsyncSupported(true);
    }
}
