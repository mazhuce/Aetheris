package com.aetheris.serving.config;

import com.aetheris.security.protocol.TimePolicy;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import com.aetheris.serving.security.ServingIdentityInterceptor;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.net.URI;
import java.time.*;
import java.util.*;

@Configuration(proxyBeanMethods=false)
public class ServingV2SecurityConfiguration {
    private static final Set<String> AUTH=Set.of("auth.session.check","auth.permission.check","auth.permission.batch","auth.permission.manage","auth.permission.mutate");
    public static final Set<String> METADATA=Set.of("metadata.user.sql-query","metadata.user.table-query","metadata.user.metric-query","metadata.published-asset.resolve","metadata.published-key.query");
    @Bean public ProtocolInstallation servingProtocolInstallation(Environment env){
        if(!Set.of("","/").contains(env.getProperty("server.servlet.context-path","")))throw new IllegalStateException("Serving v2 requires root context");
        return ProtocolInstallation.load("serving",Path.of(required(env,"registry-file")),required(env,"workload-kid"),Path.of(required(env,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment env,String suffix){String value=env.getRequiredProperty("aetheris.security."+suffix);if(value.isBlank())throw new IllegalStateException("Incomplete serving security installation");return value;}
    @Bean(name="servingAuthTransport",destroyMethod="close") public SignedHttpClient authTransport(ProtocolInstallation installation,Environment env){
        return transport(installation,"auth",env.getRequiredProperty("aetheris.auth.base-url"),AUTH,Duration.ofSeconds(2),256*1024);
    }
    @Bean(name="servingMetadataTransport",destroyMethod="close") public SignedHttpClient metadataTransport(ProtocolInstallation installation,Environment env){
        return transport(installation,"metadata",env.getRequiredProperty("aetheris.metadata.base-url"),METADATA,Duration.ofSeconds(45),1024*1024);
    }
    private SignedHttpClient transport(ProtocolInstallation installation,String target,String origin,Set<String> operations,Duration timeout,int response){
        return new SignedHttpClient("serving",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),Map.of(target,URI.create(origin)),Map.of(target,operations),
                new SignedHttpClient.Limits(256*1024,response,16,Duration.ofMillis(500),timeout));
    }
    @Bean public AuthProtocolClient servingAuthClient(@Qualifier("servingAuthTransport") SignedHttpClient transport){return new AuthProtocolClient(transport);}
    @Bean public SessionValidator servingSessionValidator(AuthProtocolClient auth){return(uid,name,av)->auth.sessionActive(uid,name,av).get();}
    @Bean public PostgresNonceStore servingNonceStore(DataSource dataSource){return new PostgresNonceStore(dataSource,"serving",Clock.systemUTC());}
    @Bean public FilterRegistrationBean<WorkloadProofFilter> servingWorkloadProofFilter(ProtocolInstallation installation,PostgresNonceStore nonces,SessionValidator sessions){
        var bean=new FilterRegistrationBean<>(new WorkloadProofFilter("serving",installation.verifier(),installation.workloadSigner(),workloadRoutes(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults()));
        bean.setName("servingWorkloadProofFilter");configure(bean,20);return bean;
    }
    public static RoutePolicy workloadRoutes(){return(method,path)->"POST".equals(method)&&"/internal/v2/published-key-admissions/redeem".equals(path)
            ?Optional.of(new RoutePolicy.Route("serving.published-key.redeem",Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN)):Optional.empty();}
    public static DirectRoutePolicy directRoutes(){return(method,path)->path.equals("/api/analytics")||path.startsWith("/api/analytics/")||path.equals("/api/serving")||path.startsWith("/api/serving/")?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT;}
    @Bean public FilterRegistrationBean<Filter> servingDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions){
        var bean=new FilterRegistrationBean<>(directFilter(installation.verifier(),sessions));bean.setName("servingDirectUserProofFilter");configure(bean,21);return bean;
    }
    public static Filter directFilter(com.aetheris.security.protocol.ProtocolVerifier verifier,SessionValidator sessions){
        var delegate=new DirectUserProofFilter("serving",verifier,sessions,directRoutes());
        return(input,output,chain)->{
            if(input instanceof HttpServletRequest request&&ServingIdentityInterceptor.apiKeyRoute(request.getMethod(),request.getRequestURI())
                    &&request.getHeader("X-API-Key")!=null&&!request.getHeader("X-API-Key").isBlank()){
                if(request.getDispatcherType()!=DispatcherType.REQUEST||!request.getContextPath().isEmpty()||request.getAttribute(TrustedUser.ATTRIBUTE) instanceof TrustedUser){((HttpServletResponse)output).setStatus(400);return;}
                request.removeAttribute(TrustedUser.ATTRIBUTE);
                chain.doFilter(request,output);return;
            }
            delegate.doFilter(input,output,chain);
        };
    }
    private static void configure(FilterRegistrationBean<?> bean,int offset){bean.setOrder(Ordered.HIGHEST_PRECEDENCE+offset);bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);}
}
