package com.aetheris.auth.config;

import com.aetheris.auth.security.*;
import com.aetheris.auth.service.AuthUserService;
import com.aetheris.security.servlet.*;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthV2SecurityConfigurationTest {
    @Test void browserSessionAdmissionIsGatewayOnlyAndCannotCarryUserProof() {
        String path = "/internal/v2/browser-sessions/check";
        var route = AuthV2SecurityConfiguration.workloadRoutes().resolve("POST", path).orElseThrow();
        assertEquals(java.util.Set.of("gateway"), route.allowedCallers());
        assertEquals("auth.browser-session.check", route.operation());
        assertEquals(RoutePolicy.UserMode.FORBIDDEN, route.userMode());
        assertTrue(AuthV2SecurityConfiguration.workloadRoutes().resolve("GET", path).isEmpty());
        assertTrue(AuthV2SecurityConfiguration.workloadRoutes().resolve("POST", path + "/neighbor").isEmpty());
    }
    @Test void onlyMetadataMayAskForGlobalQueryAuditReadCapability() {
        assertTrue(AuthV2SecurityConfiguration.resourceAllowed("metadata","query_audit","view"));
        for (String caller : java.util.List.of("gateway","system","serving","workflow","quality","ingestion","governance","ai-agent"))
            assertFalse(AuthV2SecurityConfiguration.resourceAllowed(caller,"query_audit","view"));
        for (String action : java.util.List.of("query","create","update","delete","execute","grant","write"))
            assertFalse(AuthV2SecurityConfiguration.resourceAllowed("metadata","query_audit",action));
    }
    @Test void missingBlankBadFilesAndNonRootContextFailStartup() {
        var config=new AuthV2SecurityConfiguration();
        assertThrows(IllegalStateException.class,()->config.authProtocolInstallation(new MockEnvironment()));
        assertThrows(IllegalStateException.class,()->config.authProtocolInstallation(new MockEnvironment().withProperty("aetheris.security.registry-file"," ")));
        assertThrows(IllegalStateException.class,()->config.authProtocolInstallation(new MockEnvironment().withProperty("server.servlet.context-path","/auth")));
        assertThrows(RuntimeException.class,()->config.authProtocolInstallation(new MockEnvironment()
                .withProperty("aetheris.security.registry-file","missing-registry.json").withProperty("aetheris.security.workload-kid","auth-key")
                .withProperty("aetheris.security.workload-private-file","missing-private.pem")));
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(AuthV2SecurityConfiguration.class);
            assertThrows(org.springframework.beans.BeansException.class,context::refresh);
        }
    }
    @Test void onlyExactPostPublicAndEveryInternalRouteIsFixed() {
        var routes=AuthV2SecurityConfiguration.directRoutes();
        for(String path:java.util.List.of("/api/auth/login","/api/auth/refresh","/api/auth/logout")) {
            assertEquals(DirectRoutePolicy.Decision.PUBLIC,routes.resolve("POST",path));
            for(String method:java.util.List.of("GET","PUT","HEAD","OPTIONS","DELETE")) assertEquals(DirectRoutePolicy.Decision.PROTECTED,routes.resolve(method,path));
            assertNotEquals(DirectRoutePolicy.Decision.PUBLIC,routes.resolve("POST",path+"/neighbor"));
        }
        assertEquals(DirectRoutePolicy.Decision.REJECT,routes.resolve("POST","/api/other/login"));
        assertTrue(AuthV2SecurityConfiguration.workloadRoutes().resolve("POST","/internal/v2/permissions/principal").isEmpty());
        assertTrue(AuthV2SecurityConfiguration.workloadRoutes().resolve("GET","/internal/v2/permissions/version").isEmpty());
        var route=AuthV2SecurityConfiguration.workloadRoutes().resolve("POST","/internal/v2/permissions/may-manage").orElseThrow();
        assertEquals("auth.permission.manage",route.operation());assertEquals(RoutePolicy.UserMode.REQUIRED,route.userMode());
        assertEquals(7,route.allowedCallers().size());assertFalse(route.allowedCallers().contains("auth"));assertFalse(route.allowedCallers().contains("metadata"));
    }
    @Test void callerResourceAndMutationAclDoesNotGrantAdjacentServices() {
        assertTrue(AuthV2SecurityConfiguration.resourceAllowed("metadata","table","query"));
        assertFalse(AuthV2SecurityConfiguration.resourceAllowed("metadata","workflow_task","execute"));
        assertTrue(AuthV2SecurityConfiguration.resourceAllowed("serving","datasource","query"));
        assertFalse(AuthV2SecurityConfiguration.resourceAllowed("serving","datasource","write"));
        assertFalse(AuthV2SecurityConfiguration.resourceAllowed("serving","table","update"));
        assertTrue(AuthV2SecurityConfiguration.resourceAllowed("workflow","quality_rule","execute"));
        for(String action:java.util.List.of("query","view","update","create","delete"))assertFalse(AuthV2SecurityConfiguration.resourceAllowed("workflow","quality_rule",action));
        assertFalse(AuthV2SecurityConfiguration.resourceAllowed("ingestion","quality_rule","execute"));
        assertFalse(AuthV2SecurityConfiguration.resourceAllowed("gateway","table","query"));
        assertTrue(AuthV2SecurityConfiguration.mutationAllowed("metadata","metadata"));
        assertFalse(AuthV2SecurityConfiguration.mutationAllowed("metadata","system"));
        assertTrue(AuthV2SecurityConfiguration.mutationAllowed("gateway","metadata"));
        assertFalse(AuthV2SecurityConfiguration.mutationAllowed("gateway","auth"));
    }
    @Test void realDirectFilterProtectsWrongPublicMethodAndNeighbor() throws Exception {
        var proof=new V2ProofFixture();var filter=proof.direct((uid,name,av)->true);
        for(String path:java.util.List.of("/api/auth/login","/api/auth/login-extra","/api/auth/login/neighbor")) {
            var response=new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET",path),response,(r,s)->fail("Unauthorized chain"));
            assertEquals(401,response.getStatus());
        }
        var response=new MockHttpServletResponse();var invoked=new java.util.concurrent.atomic.AtomicBoolean();
        filter.doFilter(new MockHttpServletRequest("POST","/api/auth/login"),response,(r,s)->invoked.set(true));
        assertTrue(invoked.get());
    }
    @Test void actualRegistrationCoversDispatchersInOrderWithoutAsync() throws Exception {
        // Exercise the real registration beans rather than duplicating their expected settings.
        var config=new AuthV2SecurityConfiguration();var users=mock(AuthUserService.class);
        var sessions=new AuthSessionValidator(users);var datasource=mock(DataSource.class);
        var store=config.authNonceStore(datasource);
        var proof=new V2ProofFixture();
        assertEquals(java.util.List.of("/*"),new java.util.ArrayList<>(config.authDirectUserProofFilter(
                installation(proof),sessions).getUrlPatterns()));
        var workload=config.authWorkloadProofFilter(installation(proof),store,sessions);
        var direct=config.authDirectUserProofFilter(installation(proof),sessions);
        assertTrue(workload.getOrder()<direct.getOrder());assertFalse(workload.isAsyncSupported());assertFalse(direct.isAsyncSupported());
        var dispatchers=new java.util.ArrayList<java.util.EnumSet<DispatcherType>>();
        var dynamic=(jakarta.servlet.FilterRegistration.Dynamic)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{jakarta.servlet.FilterRegistration.Dynamic.class},(p,m,a)->{
                    if(m.getName().equals("addMappingForUrlPatterns"))dispatchers.add((java.util.EnumSet<DispatcherType>)a[0]);
                    if(m.getReturnType()==boolean.class)return true;
                    if(m.getName().equals("getName"))return "auth";
                    if(m.getReturnType()==java.util.Set.class)return java.util.Set.of();
                    return null;});
        var servlet=(jakarta.servlet.ServletContext)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.ServletContext.class},
                (p,m,a)->m.getName().equals("addFilter")?dynamic:null);
        workload.onStartup(servlet);direct.onStartup(servlet);
        assertEquals(2,dispatchers.size());for(var set:dispatchers)assertEquals(java.util.EnumSet.allOf(DispatcherType.class),set);
        verifyNoInteractions(datasource);
    }
    @Test void outboundBeanRequiresFixedSystemOriginAndOnlyAuditOperation() throws Exception {
        var config=new AuthV2SecurityConfiguration();var installed=installation(new V2ProofFixture());
        assertThrows(IllegalStateException.class,()->config.authSystemTransport(installed,new MockEnvironment()));
        assertThrows(RuntimeException.class,()->config.authSystemTransport(installed,new MockEnvironment().withProperty("aetheris.system.base-url","http://localhost/path")));
        try(var client=config.authSystemTransport(installed,new MockEnvironment().withProperty("aetheris.system.base-url","http://127.0.0.1:9"))) {
            assertThrows(com.aetheris.security.protocol.transport.SignedHttpClient.TransportException.class,()->client.sendAsync(
                    new com.aetheris.security.protocol.transport.SignedHttpClient.Request("system","POST","/internal/v2/other","system.other",new byte[0])));
            assertThrows(com.aetheris.security.protocol.transport.SignedHttpClient.TransportException.class,()->client.sendAsync(
                    new com.aetheris.security.protocol.transport.SignedHttpClient.Request("system","POST","/internal/v2/audit/permission-events","system.permission-event.record",new byte[16385])));
        }
    }
    @Test void actualSpringWiringCreatesFiltersLocalSessionsNonceAndTransportAndCloses() throws Exception {
        var users=mock(AuthUserService.class);var datasource=mock(DataSource.class);
        installation(new V2ProofFixture(),environment->{
            environment.withProperty("aetheris.system.base-url","http://127.0.0.1:9");
            try(var context=new AnnotationConfigApplicationContext()) {
                context.setEnvironment(environment);
                context.registerBean(AuthUserService.class,()->users);context.registerBean(DataSource.class,()->datasource);
                context.register(AuthV2SecurityConfiguration.class,AuthSessionValidator.class,com.aetheris.auth.audit.PermissionAuditClient.class);
                context.refresh();
                assertNotNull(context.getBean("authWorkloadProofFilter"));assertNotNull(context.getBean("authDirectUserProofFilter"));
                assertInstanceOf(PostgresNonceStore.class,context.getBean("authNonceStore"));
                assertNotNull(context.getBean(com.aetheris.auth.audit.PermissionAuditClient.class));
            }
            return true;
        });
        verifyNoInteractions(datasource,users);
    }
    private com.aetheris.security.protocol.configuration.ProtocolInstallation installation(V2ProofFixture proof) throws Exception {
        return installation(proof,environment->new AuthV2SecurityConfiguration().authProtocolInstallation(environment));
    }
    private <T> T installation(V2ProofFixture proof,java.util.function.Function<MockEnvironment,T> action) throws Exception {
        var folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("target"),"auth-v2-install-");
        var registry=folder.resolve("registry.json");var privateKey=folder.resolve("auth.pem");
        try {
            var entries=new java.util.ArrayList<java.util.Map<String,String>>();
            for(String name:java.util.List.of("user","auth")) entries.add(java.util.Map.of("kid",name+"-key","purpose",name.equals("user")?"USER":"WORKLOAD",
                    "caller",name.equals("user")?"gateway":"auth","publicKey",pem("PUBLIC KEY",proof.keys.get(name).getPublic().getEncoded())));
            java.nio.file.Files.writeString(registry,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("schemaVersion",1,"keys",entries)));
            java.nio.file.Files.writeString(privateKey,pem("PRIVATE KEY",proof.keys.get("auth").getPrivate().getEncoded()));
            return action.apply(new MockEnvironment().withProperty("aetheris.security.registry-file",registry.toString())
                    .withProperty("aetheris.security.workload-kid","auth-key").withProperty("aetheris.security.workload-private-file",privateKey.toString()));
        }finally{java.nio.file.Files.deleteIfExists(privateKey);java.nio.file.Files.deleteIfExists(registry);java.nio.file.Files.deleteIfExists(folder);}
    }
    private static String pem(String type,byte[] bytes){return "-----BEGIN "+type+"-----\n"+java.util.Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+type+"-----\n";}
}
