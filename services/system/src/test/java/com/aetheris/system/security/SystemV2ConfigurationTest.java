package com.aetheris.system.security;

import com.aetheris.system.config.SystemV2SecurityConfiguration;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SystemV2ConfigurationTest {
    @TempDir Path directory;
    @Test void missingFilesAndNonRootContextFailClosedBeforeServing() {
        var config=new SystemV2SecurityConfiguration();assertThrows(RuntimeException.class,()->config.systemProtocolInstallation(new MockEnvironment()));
        assertThrows(RuntimeException.class,()->config.systemProtocolInstallation(new MockEnvironment().withProperty("server.servlet.context-path","/other")));
        var env=new MockEnvironment().withProperty("aetheris.security.registry-file",directory.resolve("missing.json").toString()).withProperty("aetheris.security.workload-kid","system-test").withProperty("aetheris.security.workload-private-file",directory.resolve("missing.pem").toString());
        assertThrows(RuntimeException.class,()->config.systemProtocolInstallation(env));
    }
    @Test void actualInstallationBuildsOrderedAllDispatcherFiltersWithNoLegacyBean() throws Exception {
        var generator=KeyPairGenerator.getInstance("Ed25519");var user=generator.generateKeyPair();var system=generator.generateKeyPair();var auth=generator.generateKeyPair();
        Path registry=directory.resolve("registry.json"),privateKey=directory.resolve("system.pem");
        Files.writeString(registry,V2TestFixture.JSON.writeValueAsString(Map.of("schemaVersion",1,"keys",List.of(
                Map.of("kid","gateway-user","purpose","USER","caller","gateway","publicKey",pem("PUBLIC",user.getPublic().getEncoded())),
                Map.of("kid","system-workload","purpose","WORKLOAD","caller","system","publicKey",pem("PUBLIC",system.getPublic().getEncoded())),
                Map.of("kid","auth-workload","purpose","WORKLOAD","caller","auth","publicKey",pem("PUBLIC",auth.getPublic().getEncoded()))))));
        Files.writeString(privateKey,pem("PRIVATE",system.getPrivate().getEncoded()));var dataSource=mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(SystemV2SecurityConfiguration.class).withBean(DataSource.class,()->dataSource)
                .withPropertyValues("aetheris.security.registry-file="+registry,"aetheris.security.workload-kid=system-workload","aetheris.security.workload-private-file="+privateKey,"aetheris.auth.base-url=http://127.0.0.1:1")
                .run(context->{
                    assertNull(context.getStartupFailure());assertEquals("system",context.getBean(ProtocolInstallation.class).service());
                    var workload=context.getBean("systemWorkloadProofFilter",FilterRegistrationBean.class);var direct=context.getBean("systemDirectUserProofFilter",FilterRegistrationBean.class);
                    for(var filter:List.of(workload,direct)){assertEquals(EnumSet.allOf(DispatcherType.class),filter.determineDispatcherTypes());assertEquals(List.of("/*"),List.copyOf(filter.getUrlPatterns()));assertFalse(filter.isAsyncSupported());}
                    assertTrue(workload.getOrder()<direct.getOrder());assertEquals(0,context.getBeansOfType(InternalTokenInterceptor.class).size());verifyNoInteractions(dataSource);
                });
        Files.delete(privateKey);Files.delete(registry);assertFalse(Files.exists(privateKey));
    }
    @Test void routePolicyNeverCreatesPublicSystemOrWildcardInternalRoutes() {
        for(String method:List.of("GET","POST","PUT","DELETE"))for(String path:List.of("/api/system","/api/system/users","/api/system/ping"))
            assertEquals(com.aetheris.security.servlet.DirectRoutePolicy.Decision.PROTECTED,SystemV2SecurityConfiguration.directRoutes().resolve(method,path));
        assertTrue(SystemV2SecurityConfiguration.workloadRoutes().resolve("GET","/internal/v2/audit/logs").isEmpty());
        assertTrue(SystemV2SecurityConfiguration.workloadRoutes().resolve("POST","/internal/v2/audit/logs/extra").isEmpty());
    }
    static String pem(String label,byte[] bytes){return "-----BEGIN "+label+" KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(bytes)+"\n-----END "+label+" KEY-----\n";}
}
