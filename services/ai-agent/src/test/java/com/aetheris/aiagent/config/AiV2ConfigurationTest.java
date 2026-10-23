package com.aetheris.aiagent.config;

import com.aetheris.aiagent.security.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiV2ConfigurationTest {
    @Test void missingBlankFilesAndNonRootContextFailClosed() {
        var config=new AiV2SecurityConfiguration();
        assertThrows(IllegalStateException.class,()->config.aiProtocolInstallation(new MockEnvironment()));
        assertThrows(IllegalStateException.class,()->config.aiProtocolInstallation(new MockEnvironment().withProperty("aetheris.security.registry-file"," ")));
        assertThrows(IllegalStateException.class,()->config.aiProtocolInstallation(new MockEnvironment().withProperty("server.servlet.context-path","/ai")));
        try(var app=new AnnotationConfigApplicationContext()){app.register(AiV2SecurityConfiguration.class);assertThrows(org.springframework.beans.BeansException.class,app::refresh);}
    }
    @Test void actualInstallationHasOnlyFixedTransportsAndExplicitAsyncDispatchPolicy() throws Exception {
        var keys=new AiTestKeys();Path owned=Files.createTempDirectory(Path.of("target"),"ai-v2-install-");
        try {
            var entries=new ArrayList<Map<String,String>>();
            for(var name:List.of("user","ai-agent","metadata","auth"))entries.add(Map.of("kid",name,"purpose",name.equals("user")?"USER":"WORKLOAD","caller",name.equals("user")?"gateway":name,
                    "publicKey",pem("PUBLIC KEY",keys.pairs.get(name).getPublic().getEncoded())));
            Path registry=owned.resolve("registry.json"),key=owned.resolve("ai.pem");
            Files.writeString(registry,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("schemaVersion",1,"keys",entries)));
            Files.writeString(key,pem("PRIVATE KEY",keys.pairs.get("ai-agent").getPrivate().getEncoded()));
            var env=new MockEnvironment().withProperty("aetheris.security.registry-file",registry.toString()).withProperty("aetheris.security.workload-kid","ai-agent")
                    .withProperty("aetheris.security.workload-private-file",key.toString()).withProperty("aetheris.auth.base-url","http://127.0.0.1:1").withProperty("aetheris.metadata.base-url","http://127.0.0.1:1");
            var source=mock(DataSource.class);
            try(var app=new AnnotationConfigApplicationContext()) {
                app.setEnvironment(env);app.registerBean(DataSource.class,()->source);app.register(AiV2SecurityConfiguration.class);app.refresh();
                assertInstanceOf(PostgresNonceStore.class,app.getBean("aiNonceStore"));
                var seen=new ArrayList<EnumSet<DispatcherType>>();
                var dynamic=(jakarta.servlet.FilterRegistration.Dynamic)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.FilterRegistration.Dynamic.class},(p,m,a)->{
                    if(m.getName().equals("addMappingForUrlPatterns"))seen.add((EnumSet<DispatcherType>)a[0]);
                    if(m.getReturnType()==boolean.class)return true;if(m.getReturnType()==Set.class)return Set.of();return null;});
                var servlet=(jakarta.servlet.ServletContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.ServletContext.class},(p,m,a)->m.getName().equals("addFilter")?dynamic:null);
                int last=Integer.MIN_VALUE;
                for(String name:List.of("aiWorkloadProofFilter","aiDirectUserProofFilter","aiAsyncAdmissionFilter")){
                    var bean=(org.springframework.boot.web.servlet.FilterRegistrationBean<?>)app.getBean(name);assertTrue(bean.getOrder()>last);last=bean.getOrder();assertTrue(bean.isAsyncSupported());bean.onStartup(servlet);
                }
                assertEquals(List.of(EnumSet.allOf(DispatcherType.class),EnumSet.of(DispatcherType.REQUEST,DispatcherType.FORWARD,DispatcherType.INCLUDE,DispatcherType.ERROR),EnumSet.allOf(DispatcherType.class)),seen);
                var metadata=app.getBean("aiMetadataTransport",SignedHttpClient.class);assertSame(metadata,app.getBean("aiMetadataTransport"));
                var auth=app.getBean("aiAuthTransport",SignedHttpClient.class);
                for(var transport:List.of(auth,metadata))assertThrows(SignedHttpClient.TransportException.class,()->transport.sendAsync(new SignedHttpClient.Request("system","POST","/internal/v2/x","system.x",new byte[0])));
                assertThrows(SignedHttpClient.TransportException.class,()->auth.sendAsync(new SignedHttpClient.Request("auth","POST","/internal/v2/permissions/version","auth.permission.version",new byte[0])));
                var direct=(org.springframework.boot.web.servlet.FilterRegistrationBean<?>)app.getBean("aiDirectUserProofFilter");
                for(String path:List.of("/api/ai/chatbi","/api/ai/health","/api/ai/login","/api/other")) {
                    var response=new MockHttpServletResponse();direct.getFilter().doFilter(new MockHttpServletRequest("GET",path),response,(r,s)->fail("public API fallback"));
                    assertEquals(path.equals("/api/other")?403:401,response.getStatus());
                }
                var workload=(org.springframework.boot.web.servlet.FilterRegistrationBean<?>)app.getBean("aiWorkloadProofFilter");
                for(String path:List.of("/internal/old","/internal/v2/agent/query")) {
                    var response=new MockHttpServletResponse();workload.getFilter().doFilter(new MockHttpServletRequest("POST",path),response,(r,s)->fail("internal allowroute"));assertTrue(response.getStatus()>=400);
                }
            }
            verifyNoInteractions(source);
        }finally{try(var paths=Files.walk(owned)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}assertFalse(Files.exists(owned));}
    }
    private static String pem(String type,byte[] bytes){return "-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+type+"-----\n";}
}
