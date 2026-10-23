package com.aetheris.quality.config;

import com.aetheris.quality.security.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import jakarta.servlet.DispatcherType;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QualityV2ConfigurationTest {
    @Test void missingBlankInvalidFilesAndNonRootContextFailInstallation() {
        var config=new QualityV2SecurityConfiguration();
        assertThrows(IllegalStateException.class,()->config.qualityProtocolInstallation(new MockEnvironment()));
        assertThrows(IllegalStateException.class,()->config.qualityProtocolInstallation(new MockEnvironment().withProperty("aetheris.security.registry-file"," ")));
        assertThrows(IllegalStateException.class,()->config.qualityProtocolInstallation(new MockEnvironment().withProperty("server.servlet.context-path","/quality")));
        assertThrows(com.aetheris.security.protocol.ProtocolException.class,()->config.qualityProtocolInstallation(new MockEnvironment()
                .withProperty("aetheris.security.registry-file","target/does-not-exist-registry.json").withProperty("aetheris.security.workload-kid","quality")
                .withProperty("aetheris.security.workload-private-file","target/does-not-exist-key.pem")));
        try(var context=new AnnotationConfigApplicationContext()) {context.register(QualityV2SecurityConfiguration.class);assertThrows(org.springframework.beans.BeansException.class,context::refresh);}
    }
    @Test void actualSpringConfigurationLoadsKeysConstructsAdaptersAndRegistersEveryDispatcher() throws Exception {
        try(var fixture=new V2QualityFixture()) {
            var datasource=mock(DataSource.class);
            installation(fixture,environment->{
                try(var context=new AnnotationConfigApplicationContext()) {
                    context.setEnvironment(environment);context.registerBean(DataSource.class,()->datasource);
                    context.register(QualityV2SecurityConfiguration.class,SecurityWebConfig.class,ResourceAuthorization.class,GatewayIdentityInterceptor.class,
                            com.aetheris.quality.controller.RecoveryAdminGate.class,com.aetheris.quality.service.NotificationClient.class);context.refresh();
                    assertNotNull(context.getBean(com.aetheris.quality.controller.RecoveryAdminGate.class));
                    assertEquals(0,context.getBean(com.aetheris.quality.service.NotificationClient.class).stats().queued());
                    assertNotNull(context.getBean(com.aetheris.security.protocol.authorization.AuthProtocolClient.class));
                    var workload=(org.springframework.boot.web.servlet.FilterRegistrationBean<?>)context.getBean("qualityWorkloadProofFilter");
                    var direct=(org.springframework.boot.web.servlet.FilterRegistrationBean<?>)context.getBean("qualityDirectUserProofFilter");
                    assertTrue(workload.getOrder()<direct.getOrder());assertFalse(workload.isAsyncSupported());assertFalse(direct.isAsyncSupported());
                    var seen=new ArrayList<EnumSet<DispatcherType>>();
                    var registration=(jakarta.servlet.FilterRegistration.Dynamic)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.FilterRegistration.Dynamic.class},(p,m,a)->{
                        if(m.getName().equals("addMappingForUrlPatterns"))seen.add((EnumSet<DispatcherType>)a[0]);
                        if(m.getReturnType()==boolean.class)return true;if(m.getReturnType()==Set.class)return Set.of();return null;});
                    var servlet=(jakarta.servlet.ServletContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.ServletContext.class},(p,m,a)->m.getName().equals("addFilter")?registration:null);
                    try{workload.onStartup(servlet);direct.onStartup(servlet);}catch(jakarta.servlet.ServletException error){throw new IllegalStateException(error);}
                    assertEquals(List.of(EnumSet.allOf(DispatcherType.class),EnumSet.allOf(DispatcherType.class)),seen);
                    var transport=context.getBean("qualityAuthTransport",SignedHttpClient.class);
                    assertThrows(SignedHttpClient.TransportException.class,()->transport.sendAsync(new SignedHttpClient.Request("auth","POST","/internal/v2/permissions/version","auth.permission.version",new byte[0])));
                    assertThrows(SignedHttpClient.TransportException.class,()->transport.sendAsync(new SignedHttpClient.Request("system","POST","/internal/v2/anything","system.anything",new byte[0])));
                    var system=context.getBean("qualitySystemTransport",SignedHttpClient.class);assertSame(system,context.getBean("qualitySystemTransport"));
                    assertThrows(SignedHttpClient.TransportException.class,()->system.sendAsync(new SignedHttpClient.Request("system","POST","/internal/v2/audit/permission-events","system.permission-event.record",new byte[0])));
                    var metadata=context.getBean("qualityMetadataTransport",SignedHttpClient.class);assertSame(metadata,context.getBean("qualityMetadataTransport"));
                    assertThrows(SignedHttpClient.TransportException.class,()->metadata.sendAsync(new SignedHttpClient.Request("metadata","POST","/internal/v2/query","metadata.user.sql-query",new byte[0])));
                    assertInstanceOf(PostgresNonceStore.class,context.getBean("qualityNonceStore"));
                }
                return true;
            });verifyNoInteractions(datasource);
        }
    }
    @Test void serviceHasNoPublicApiExclusionsOrInternalRoutes() {
        var routes=QualityV2SecurityConfiguration.directRoutes();
        for(String method:List.of("GET","POST","DELETE","HEAD","OPTIONS"))for(String path:List.of("/api/quality/rules","/api/quality/health","/api/quality/login"))
            assertEquals(DirectRoutePolicy.Decision.PROTECTED,routes.resolve(method,path));
        assertEquals(DirectRoutePolicy.Decision.REJECT,routes.resolve("GET","/api/other"));
        var internal=QualityV2SecurityConfiguration.workloadRoutes();assertTrue(internal.resolve("POST","/internal/v2/executions/lookup").isPresent());
        for(String method:List.of("GET","HEAD","OPTIONS","DELETE"))assertTrue(internal.resolve(method,"/internal/v2/executions/lookup").isEmpty());
        for(String path:List.of("/internal/quality/rules/8/run","/internal/quality/rules/8/latest","/internal/v2/executions/lookup/","/internal/v2/executions/run"))assertTrue(internal.resolve("POST",path).isEmpty());
    }
    private <T> T installation(V2QualityFixture fixture,Function<MockEnvironment,T> action) throws Exception {
        Path folder=Files.createTempDirectory(Path.of("target"),"quality-v2-install-");Path registry=folder.resolve("registry.json"),key=folder.resolve("quality.pem");
        try {
            var entries=new ArrayList<Map<String,String>>();
            for(String name:List.of("user","auth","quality"))entries.add(Map.of("kid",name,"purpose",name.equals("user")?"USER":"WORKLOAD","caller",name.equals("user")?"gateway":name,"publicKey",pem("PUBLIC KEY",fixture.keys.get(name).getPublic().getEncoded())));
            Files.writeString(registry,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("schemaVersion",1,"keys",entries)));
            Files.writeString(key,pem("PRIVATE KEY",fixture.keys.get("quality").getPrivate().getEncoded()));
            return action.apply(new MockEnvironment().withProperty("aetheris.security.registry-file",registry.toString()).withProperty("aetheris.security.workload-kid","quality")
                    .withProperty("aetheris.security.workload-private-file",key.toString()).withProperty("aetheris.auth.base-url","http://127.0.0.1:"+fixture.server.getAddress().getPort()).withProperty("aetheris.metadata.base-url","http://127.0.0.1:"+fixture.server.getAddress().getPort()).withProperty("aetheris.system.base-url","http://127.0.0.1:"+fixture.server.getAddress().getPort()));
        }finally{Files.deleteIfExists(key);Files.deleteIfExists(registry);Files.deleteIfExists(folder);assertFalse(Files.exists(folder));}
    }
    private static String pem(String type,byte[] raw){return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(raw)+"\n-----END "+type+"-----\n";}
}
