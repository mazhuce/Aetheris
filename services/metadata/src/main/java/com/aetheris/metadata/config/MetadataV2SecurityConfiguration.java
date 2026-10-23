package com.aetheris.metadata.config;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import com.aetheris.metadata.fixedexecution.FixedTransferPeers;
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
import java.util.concurrent.TimeUnit;

/** Enumerated USER queries and the ingestion-only durable transfer contract; no legacy execute route. */
@Configuration(proxyBeanMethods=false)
public class MetadataV2SecurityConfiguration {
    public static RoutePolicy workloadRoutes() {
        return (method,path)->{
            if(!"POST".equals(method))return Optional.empty();
            return switch(path) {
                case "/internal/v2/agent/tables/search" -> user("metadata.agent.search",Set.of("ai-agent"));
                case "/internal/v2/agent/tables/describe" -> user("metadata.agent.describe",Set.of("ai-agent"));
                case "/internal/v2/agent/query" -> user("metadata.user.sql-query",Set.of("ai-agent","serving"));
                case "/internal/v2/tables/query" -> user("metadata.user.table-query",Set.of("serving"));
                case "/internal/v2/semantic/metrics/list" -> user("metadata.agent.metric-list",Set.of("ai-agent"));
                case "/internal/v2/semantic/metrics/query" -> user("metadata.user.metric-query",Set.of("ai-agent","serving"));
                case "/internal/v2/published-api-assets/resolve" -> Optional.of(new RoutePolicy.Route("metadata.published-asset.resolve",Set.of("serving"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/published-api-assets/key-query" -> Optional.of(new RoutePolicy.Route("metadata.published-key.query",Set.of("serving"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/executions/transfer/submit" -> fixed("metadata.fixed-transfer.submit");
                case "/internal/v2/executions/quality-query/submit" -> Optional.of(new RoutePolicy.Route("metadata.fixed-quality-query.submit",Set.of("quality"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/executions/sql/submit" -> Optional.of(new RoutePolicy.Route("metadata.fixed-sql.submit",Set.of("workflow"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/sql/validate-write" -> Optional.of(new RoutePolicy.Route("metadata.fixed-sql.validate-write",Set.of("workflow"),RoutePolicy.UserMode.FORBIDDEN));
                case "/internal/v2/executions/status" -> Optional.of(new RoutePolicy.Route("metadata.fixed-execution.status",Set.of("ingestion","quality","workflow"),RoutePolicy.UserMode.FORBIDDEN));
                default -> Optional.empty();
            };
        };
    }
    private static Optional<RoutePolicy.Route> fixed(String operation) {
        return Optional.of(new RoutePolicy.Route(operation,Set.of("ingestion"),RoutePolicy.UserMode.FORBIDDEN));
    }
    private static Optional<RoutePolicy.Route> user(String operation,Set<String> callers) {
        return Optional.of(new RoutePolicy.Route(operation,callers,RoutePolicy.UserMode.REQUIRED));
    }
    @Bean public ProtocolInstallation metadataProtocolInstallation(Environment env) {
        String context=env.getProperty("server.servlet.context-path","");
        if(!context.isEmpty()&&!context.equals("/"))throw new IllegalStateException("Metadata v2 requires root context");
        return ProtocolInstallation.load("metadata",Path.of(required(env,"registry-file")),required(env,"workload-kid"),
                Path.of(required(env,"workload-private-file")),null,null,Clock.systemUTC());
    }
    private static String required(Environment env,String suffix) {
        String value=env.getRequiredProperty("aetheris.security."+suffix);
        if(value.isBlank())throw new IllegalStateException("Missing metadata protocol installation");return value;
    }
    @Bean(destroyMethod="close") public SignedHttpClient metadataAuthTransport(ProtocolInstallation installation,Environment env) {
        return new SignedHttpClient("metadata",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("auth",URI.create(env.getRequiredProperty("aetheris.auth.base-url"))),
                Map.of("auth",Set.of("auth.session.check","auth.permission.check","auth.permission.batch","auth.permission.visibility","auth.fixed-execution.authorize","auth.published-key-query.authorize")),
                new SignedHttpClient.Limits(1024*1024,1024*1024,32,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean(destroyMethod="close") public SignedHttpClient metadataIngestionTransport(ProtocolInstallation installation,Environment env) {
        return new SignedHttpClient("metadata",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("ingestion",URI.create(env.getRequiredProperty("aetheris.ingestion.base-url"))),
                Map.of("ingestion",Set.of("ingestion.execution.lookup")),
                new SignedHttpClient.Limits(1024*1024,1024*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public FixedTransferPeers metadataFixedTransferPeers(@Qualifier("metadataIngestionTransport") SignedHttpClient source,
            @Qualifier("metadataAuthTransport") SignedHttpClient auth){return new FixedTransferPeers(source,auth,Clock.systemUTC());}
    @Bean(destroyMethod="close") public SignedHttpClient metadataQualityTransport(ProtocolInstallation installation,Environment env) {
        return new SignedHttpClient("metadata",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("quality",URI.create(env.getRequiredProperty("aetheris.quality.base-url"))),Map.of("quality",Set.of("quality.execution.lookup")),
                new SignedHttpClient.Limits(1024*1024,1024*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public com.aetheris.metadata.fixedexecution.FixedQualityPeers metadataFixedQualityPeers(@Qualifier("metadataQualityTransport") SignedHttpClient source,
            @Qualifier("metadataAuthTransport") SignedHttpClient auth){return new com.aetheris.metadata.fixedexecution.FixedQualityPeers(source,auth,Clock.systemUTC());}
    @Bean(destroyMethod="close") public SignedHttpClient metadataWorkflowTransport(ProtocolInstallation installation,Environment env){
        return new SignedHttpClient("metadata",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
            Map.of("workflow",URI.create(env.getRequiredProperty("aetheris.workflow.base-url"))),Map.of("workflow",Set.of("workflow.execution.lookup")),
            new SignedHttpClient.Limits(65536,1024*1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public com.aetheris.metadata.fixedexecution.FixedSqlPeers metadataFixedSqlPeers(@Qualifier("metadataWorkflowTransport") SignedHttpClient source,@Qualifier("metadataAuthTransport") SignedHttpClient auth){return new com.aetheris.metadata.fixedexecution.FixedSqlPeers(source,auth,Clock.systemUTC());}
    @Bean public AuthProtocolClient metadataAuthProtocolClient(@Qualifier("metadataAuthTransport") SignedHttpClient metadataAuthTransport) {return new AuthProtocolClient(metadataAuthTransport);}
    @Bean(destroyMethod="close") public SignedHttpClient metadataServingTransport(ProtocolInstallation installation,Environment env) {
        return new SignedHttpClient("metadata",installation.workloadSigner(),installation.verifier(),Clock.systemUTC(),TimePolicy.defaults(),
                Map.of("serving",URI.create(env.getRequiredProperty("aetheris.serving.base-url"))),Map.of("serving",Set.of("serving.published-key.redeem")),
                new SignedHttpClient.Limits(65536,65536,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
    }
    @Bean public com.aetheris.metadata.publishedkey.PublishedKeyPeers metadataPublishedKeyPeers(@Qualifier("metadataServingTransport") SignedHttpClient serving,
            @Qualifier("metadataAuthTransport") SignedHttpClient auth){return new com.aetheris.metadata.publishedkey.PublishedKeyPeers(serving,auth,Clock.systemUTC());}
    @Bean public SessionValidator metadataSessionValidator(AuthProtocolClient auth) {
        return (uid,name,av)->{
            var pending=auth.sessionActive(uid,name,av);
            try{return pending.get(2500,TimeUnit.MILLISECONDS);}
            catch(Exception error){pending.cancel(true);if(error instanceof InterruptedException)Thread.currentThread().interrupt();throw error;}
        };
    }
    @Bean public PostgresNonceStore metadataNonceStore(DataSource dataSource) {return new PostgresNonceStore(dataSource,"metadata",Clock.systemUTC());}
    @Bean public FilterRegistrationBean<WorkloadProofFilter> metadataWorkloadProofFilter(ProtocolInstallation installation,PostgresNonceStore nonces,SessionValidator sessions) {
        var bean=new FilterRegistrationBean<>(new WorkloadProofFilter("metadata",installation.verifier(),installation.workloadSigner(),
                workloadRoutes(),nonces,sessions,Clock.systemUTC(),TimePolicy.defaults()));
        bean.setName("metadataWorkloadProofFilter");bean.setOrder(Ordered.HIGHEST_PRECEDENCE+20);bean.addUrlPatterns("/*");
        bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);return bean;
    }
    @Bean public FilterRegistrationBean<DirectUserProofFilter> metadataDirectUserProofFilter(ProtocolInstallation installation,SessionValidator sessions) {
        var filter=new DirectUserProofFilter("metadata",installation.verifier(),sessions,(method,path)->
                path.equals("/api/metadata")||path.startsWith("/api/metadata/")?DirectRoutePolicy.Decision.PROTECTED:DirectRoutePolicy.Decision.REJECT);
        var bean=new FilterRegistrationBean<>(filter);bean.setName("metadataDirectUserProofFilter");bean.setOrder(Ordered.HIGHEST_PRECEDENCE+21);
        bean.addUrlPatterns("/*");bean.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));bean.setAsyncSupported(false);return bean;
    }
}
