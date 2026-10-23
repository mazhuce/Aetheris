package com.aetheris.system.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import com.aetheris.system.config.SystemV2SecurityConfiguration;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated JDK HTTP auth double with real signatures. No running auth service or database. */
class SystemAuthProtocolWireTest {
    @Test void operationsStrictManagementDistinguishesLiveDenialFromInvalidOrUnavailableAuth() throws Exception {
        try(var wire=new AuthWire()){
            var client=new AuthPermissionClient(wire.auth);
            var request=new MockHttpServletRequest("GET","/api/system/alerts");V2TestFixture.trusted(1,"admin").postProcessRequest(request);
            var user=TrustedUser.require(request);
            assertEquals(401,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.requireMayManage(user)).getStatusCode().value());
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try{
                assertDoesNotThrow(()->client.requireMayManage(user));
                wire.permission.set(false);
                assertEquals(403,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.requireMayManage(user)).getStatusCode().value());
                wire.permission.set(true);wire.tamper.set(true);
                assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.requireMayManage(user)).getStatusCode().value());
                assertEquals(3,wire.calls.get(),"No management caching or retry after an untrusted response");
                wire.transport.close();
                assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->client.requireMayManage(user)).getStatusCode().value());
            }finally{RequestContextHolder.resetRequestAttributes();}
        }
    }
    @Test void managementUsesOnlyTheCurrentTrustedSubjectAndSignedAuthResponse() throws Exception {
        try(var wire=new AuthWire()) {
            var client=new AuthPermissionClient(wire.auth);
            assertFalse(client.mayManage("admin"));assertEquals(0,wire.calls.get());
            var request=new MockHttpServletRequest("GET","/api/system/users");V2TestFixture.trusted(1,"admin").postProcessRequest(request);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                assertFalse(client.mayManage("alice"));assertEquals(0,wire.calls.get());
                assertTrue(client.mayManage("admin"));assertEquals(1,wire.calls.get());
                wire.permission.set(false);assertFalse(client.mayManage("admin"));
                wire.permission.set(true);wire.tamper.set(true);assertFalse(client.mayManage("admin"));
                assertEquals(3,wire.calls.get(),"Untrusted response must not trigger an automatic replay");
            }finally{RequestContextHolder.resetRequestAttributes();}
        }
    }
    @Test void nakedLegacyHeadersAndUnavailableTransportNeverBecomeManagementProof() throws Exception {
        try(var wire=new AuthWire()) {
            var request=new MockHttpServletRequest();request.addHeader("X-Aetheris-Identity",IdentityVerifierTestHelper.sign(1L,"admin","old-token"));
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {assertFalse(new AuthPermissionClient(wire.auth).mayManage("admin"));assertEquals(0,wire.calls.get());}
            finally{RequestContextHolder.resetRequestAttributes();}
            V2TestFixture.trusted(1,"admin").postProcessRequest(request);wire.transport.close();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try{assertFalse(new AuthPermissionClient(wire.auth).mayManage("admin"));}
            finally{RequestContextHolder.resetRequestAttributes();}
        }
    }
    @Test void directFilterChecksExactLiveTupleWithoutUserHeaderOnSessionOperation() throws Exception {
        try(var wire=new AuthWire()) {
            var sessions=new SystemV2SecurityConfiguration().systemSessionValidator(wire.auth);
            var filter=new DirectUserProofFilter("system",V2TestFixture.VERIFIER,sessions,SystemV2SecurityConfiguration.directRoutes());
            for(int state:List.of(1,0,2)) {
                wire.session.set(state);var request=new MockHttpServletRequest("GET","/api/system/activity");
                request.addHeader(SignedHttpClient.USER_PROOF,V2TestFixture.user(7,"alice","system"));var response=new MockHttpServletResponse();var invoked=new AtomicInteger();
                filter.doFilter(request,response,(req,res)->{assertEquals(7,TrustedUser.require((jakarta.servlet.http.HttpServletRequest)req).proof().uid());invoked.incrementAndGet();});
                assertEquals(state==1?200:state==0?403:503,response.getStatus());assertEquals(state==1?1:0,invoked.get());
            }
            assertEquals(3,wire.calls.get());assertEquals(0,wire.sessionUserHeaders.get());
        }
    }
    @Test void wrongAudienceAndExpiredProofStopBeforeRemoteSessionCall() throws Exception {
        try(var wire=new AuthWire()) {
            var filter=new DirectUserProofFilter("system",V2TestFixture.VERIFIER,new SystemV2SecurityConfiguration().systemSessionValidator(wire.auth),SystemV2SecurityConfiguration.directRoutes());
            for(String proof:List.of(V2TestFixture.user(7,"alice","metadata"),V2TestFixture.userAt(7,"alice","system",V2TestFixture.CLOCK.instant().getEpochSecond()-600))) {
                var request=new MockHttpServletRequest("GET","/api/system/activity");request.addHeader(SignedHttpClient.USER_PROOF,proof);var response=new MockHttpServletResponse();
                filter.doFilter(request,response,(a,b)->fail("Invalid proof reached chain"));assertEquals(401,response.getStatus());
            }
            assertEquals(0,wire.calls.get());
        }
    }
    static final class AuthWire implements AutoCloseable {
        final HttpServer server;final ExecutorService workers=Executors.newFixedThreadPool(2);
        final AtomicInteger calls=new AtomicInteger(),session=new AtomicInteger(1),sessionUserHeaders=new AtomicInteger();
        final AtomicBoolean permission=new AtomicBoolean(true),tamper=new AtomicBoolean();
        final SignedHttpClient transport;final AuthProtocolClient auth;
        AuthWire() throws Exception {
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setExecutor(workers);server.createContext("/internal/v2/",this::handle);server.start();
            transport=new SignedHttpClient("system",V2TestFixture.SIGNERS.get("system"),V2TestFixture.VERIFIER,V2TestFixture.CLOCK,TimePolicy.defaults(),
                    Map.of("auth",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),Map.of("auth",Set.of("auth.session.check","auth.permission.manage")),
                    new SignedHttpClient.Limits(16384,8192,4,Duration.ofMillis(500),Duration.ofSeconds(2)));
            auth=new AuthProtocolClient(transport);
        }
        void handle(HttpExchange exchange) throws java.io.IOException {
            try(exchange) {
                calls.incrementAndGet();assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getRawQuery());
                byte[] body=exchange.getRequestBody().readAllBytes();String path=exchange.getRequestURI().getRawPath();
                boolean check=path.equals("/internal/v2/sessions/check");assertTrue(check||path.equals("/internal/v2/permissions/may-manage"));
                String operation=check?"auth.session.check":"auth.permission.manage",user=exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF);
                assertEquals(1,exchange.getRequestHeaders().get(SignedHttpClient.REQUEST_PROOF).size());
                var request=V2TestFixture.VERIFIER.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("auth","POST",path,BodyHash.sha256(body),operation,BodyHash.userProof(user)));
                assertEquals("system",request.caller());var json=V2TestFixture.JSON.readTree(body);int status=200;String response;
                if(check) {
                    if(user!=null)sessionUserHeaders.incrementAndGet();assertNull(user);assertEquals(7,json.path("uid").longValue());assertEquals("alice",json.path("username").textValue());assertEquals(3,json.path("accessVersion").longValue());
                    if(session.get()==2)status=503;response="{\"active\":"+(session.get()==1)+"}";
                } else {
                    assertNotNull(user);assertEquals("admin",V2TestFixture.VERIFIER.verifyUser(user,"system").proof().username());assertEquals(0,json.size());
                    response="{\"allowed\":"+permission.get()+",\"reason\":null}";
                }
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);long now=V2TestFixture.CLOCK.instant().getEpochSecond();
                String proof=V2TestFixture.SIGNERS.get("auth").sign(new WorkloadResponseProof(2,"auth","system",request.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof);exchange.getResponseHeaders().set("Content-Type","application/json");
                if(tamper.get())bytes="{\"allowed\":true,\"reason\":\"changed\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }
        }
        @Override public void close() throws Exception {
            transport.close();int port=server.getAddress().getPort();server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));
            try(var socket=new Socket()){assertThrows(ConnectException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),500));}
        }
    }
}
