package com.aetheris.ingestion.config;

import com.aetheris.ingestion.security.*;
import com.aetheris.ingestion.service.NotificationClient;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.servlet.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionV2SecurityConfigurationTest {
    @TempDir Path directory;
    ApplicationContextRunner context(){return new ApplicationContextRunner().withUserConfiguration(IngestionV2SecurityConfiguration.class,
            ResourceAuthorization.class,GatewayIdentityInterceptor.class,NotificationClient.class)
            .withBean(javax.sql.DataSource.class,()->mock(javax.sql.DataSource.class));}
    String[] properties() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("Ed25519");var user=generator.generateKeyPair();var workload=generator.generateKeyPair();
        String publicUser=pem("PUBLIC KEY",user.getPublic().getEncoded()),publicWorkload=pem("PUBLIC KEY",workload.getPublic().getEncoded());
        Path registry=directory.resolve("registry.json"),key=directory.resolve("ingestion.pem");
        new ObjectMapper().writeValue(registry.toFile(),Map.of("schemaVersion",1,"keys",List.of(
                Map.of("kid","user","purpose","USER","caller","gateway","publicKey",publicUser),
                Map.of("kid","ingestion","purpose","WORKLOAD","caller","ingestion","publicKey",publicWorkload))));
        Files.writeString(key,pem("PRIVATE KEY",workload.getPrivate().getEncoded()));
        return new String[]{"aetheris.security.registry-file="+registry,"aetheris.security.workload-kid=ingestion",
                "aetheris.security.workload-private-file="+key,"aetheris.auth.base-url=http://127.0.0.1:1","aetheris.system.base-url=http://127.0.0.1:1","aetheris.metadata.base-url=http://127.0.0.1:1"};
    }
    static String pem(String kind,byte[] value){return "-----BEGIN "+kind+"-----\n"+Base64.getEncoder().encodeToString(value)+"\n-----END "+kind+"-----\n";}
    @Test void missingInstallationAndWrongServicePrivateKeyAreStartupErrors() throws Exception {
        context().run(c->assertNotNull(c.getStartupFailure()));
        String[] props=properties();Files.writeString(directory.resolve("ingestion.pem"),pem("PRIVATE KEY",java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate().getEncoded()));
        context().withPropertyValues(props).run(c->assertNotNull(c.getStartupFailure()));
    }
    @Test void validSingletonInstallationRegistersAllDispatchersAndNoUserSigningKey() throws Exception {
        context().withPropertyValues(properties()).run(c->{
            assertNull(c.getStartupFailure());assertTrue(c.getBean(ProtocolInstallation.class).userSigner().isEmpty());
            assertEquals(3,c.getBeansOfType(com.aetheris.security.protocol.transport.SignedHttpClient.class).size());
            for(String name:List.of("ingestionWorkloadProofFilter","ingestionDirectUserProofFilter")) {
                var bean=(FilterRegistrationBean<?>)c.getBean(name);assertFalse(bean.isAsyncSupported());assertEquals(Set.of("/*"),bean.getUrlPatterns());
                Object dispatchers=org.springframework.test.util.ReflectionTestUtils.getField(bean,"dispatcherTypes");
                assertEquals(EnumSet.allOf(jakarta.servlet.DispatcherType.class),dispatchers);
            }
            var bean=(FilterRegistrationBean<?>)c.getBean("ingestionWorkloadProofFilter");
            var routes=(RoutePolicy)org.springframework.test.util.ReflectionTestUtils.getField(bean.getFilter(),"routes");
            assertEquals(Set.of("metadata"),routes.resolve("POST","/internal/v2/executions/lookup").orElseThrow().allowedCallers());
            assertTrue(routes.resolve("POST","/internal/v2/executions/unlisted").isEmpty());
        });
    }
    @Test void directApiHasNoPublicMethodsAndNonRootContextCannotStart() throws Exception {
        for(String method:List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS")) {
            assertEquals(DirectRoutePolicy.Decision.PROTECTED,IngestionV2SecurityConfiguration.directRoutes().resolve(method,"/api/ingestion/tasks"));
            assertEquals(DirectRoutePolicy.Decision.REJECT,IngestionV2SecurityConfiguration.directRoutes().resolve(method,"/api/other/tasks"));
        }
        context().withPropertyValues(properties()).withPropertyValues("server.servlet.context-path=/nested").run(c->assertNotNull(c.getStartupFailure()));
    }
}
