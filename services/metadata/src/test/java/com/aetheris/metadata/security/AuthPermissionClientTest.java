package com.aetheris.metadata.security;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.aetheris.security.servlet.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class AuthPermissionClientTest {
    final MetadataProofFixture proof=new MetadataProofFixture();
    final ObjectMapper json=new ObjectMapper();
    final SimpleMeterRegistry metrics=new SimpleMeterRegistry();
    final AtomicReference<String> answer=new AtomicReference<>("{\"allowed\":true,\"reason\":null}");
    final AtomicReference<String> observedUser=new AtomicReference<>();
    final AtomicReference<com.fasterxml.jackson.databind.JsonNode> observedBody=new AtomicReference<>();
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    final AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger();
    final AtomicBoolean signed=new AtomicBoolean(true);
    HttpServer server;ExecutorService handlers;SignedHttpClient transport;AuthPermissionClient client;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);handlers=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(handlers);
        server.createContext("/",exchange->{
            try {
                calls.incrementAndGet();String path=exchange.getRequestURI().getRawPath();String operation=switch(path) {
                    case "/internal/v2/permissions/check"->"auth.permission.check";
                    case "/internal/v2/permissions/check-batch"->"auth.permission.batch";
                    case "/internal/v2/permissions/visibility"->"auth.permission.visibility";
                    default->throw new AssertionError("Unexpected permission route");
                };
                byte[] body=exchange.getRequestBody().readAllBytes();observedBody.set(json.readTree(body));
                assertFalse(observedBody.get().has("username"));assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                String user=exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF);observedUser.set(user);
                var request=proof.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest("auth","POST",path,BodyHash.sha256(body),operation,BodyHash.userProof(user))).proof();
                assertEquals("metadata",request.caller());byte[] response=answer.get().getBytes(StandardCharsets.UTF_8);long now=proof.clock.instant().getEpochSecond();
                if(signed.get())exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof.workload("auth").sign(
                        new WorkloadResponseProof(2,"auth","metadata",request.nonce(),status.get(),BodyHash.sha256(response),now,now+30)));
                exchange.sendResponseHeaders(status.get(),response.length);exchange.getResponseBody().write(response);
            }catch(Throwable error){serverFailure.compareAndSet(null,error);}finally{exchange.close();}
        });server.start();
        transport=new SignedHttpClient("metadata",proof.workload("metadata"),proof.verifier,proof.clock,TimePolicy.defaults(),
                Map.of("auth",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),
                Map.of("auth",Set.of("auth.permission.check","auth.permission.batch","auth.permission.visibility")),
                new SignedHttpClient.Limits(65536,65536,8,Duration.ofSeconds(1),Duration.ofSeconds(2)));
        client=new AuthPermissionClient(new AuthProtocolClient(transport),metrics);
    }
    @AfterEach void close() throws Exception {
        RequestContextHolder.resetRequestAttributes();transport.close();server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));
        if(serverFailure.get()!=null)throw new AssertionError("Auth fixture failed",serverFailure.get());metrics.close();
    }
    <T> T asUser(String audience,Supplier<T> action) throws Exception {
        String compact=proof.user(audience);var result=new AtomicReference<T>();
        var request=new MockHttpServletRequest("POST",audience.equals("metadata")?"/api/metadata/datasources":"/internal/v2/agent/query");request.addHeader(SignedHttpClient.USER_PROOF,compact);
        jakarta.servlet.Filter filter;
        if(audience.equals("metadata"))filter=proof.direct((uid,name,av)->true);
        else {byte[] body="{}".getBytes(StandardCharsets.UTF_8);request.setContent(body);
            request.addHeader(SignedHttpClient.REQUEST_PROOF,proof.request(audience,request.getRequestURI(),"metadata.user.sql-query",body,compact));
            filter=proof.filter((c,n,e)->true,(uid,name,av)->true);}
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((HttpServletRequest)req));
            try{result.set(action.get());}finally{RequestContextHolder.resetRequestAttributes();}
        });assertEquals(200,response.getStatus());return result.get();
    }
    @Test void collectionCheckUsesSignedOriginalUserAndNullResourceId() throws Exception {
        var result=asUser("metadata",()->client.check("alice","datasource",null,"view",Map.of()));
        assertTrue(result.allowed());assertTrue(result.available());assertTrue(observedBody.get().get("resourceId").isNull());
        var user=proof.verifier.verifyUser(observedUser.get(),"metadata").proof();assertEquals(42,user.uid());assertEquals(3,user.accessVersion());
    }
    @Test void metadataRelaysTheOriginalServingUserWithoutMintingAnotherIdentity() throws Exception {
        assertTrue(asUser("serving",()->client.check("alice","table",7L,"query",Map.of())).allowed());
        assertEquals("serving",proof.verifier.verifyUser(observedUser.get(),"serving").proof().audience());
    }
    @Test void noRequestOrDifferentSubjectMakesNoPermissionRpc() throws Exception {
        assertFalse(client.check("alice","table",7L,"view",Map.of()).available());
        assertFalse(asUser("metadata",()->client.check("bob","table",7L,"view",Map.of())).available());
        assertEquals(0,calls.get());
    }
    @Test void signedDenialRemainsDenialAndUnavailableOrUnsignedCannotAuthorize() throws Exception {
        answer.set("{\"allowed\":false,\"reason\":\"revoked\"}");var denied=asUser("metadata",()->client.check("alice","table",7L,"view",Map.of()));
        assertFalse(denied.allowed());assertTrue(denied.available());
        status.set(503);assertFalse(asUser("metadata",()->client.check("alice","table",7L,"view",Map.of())).available());
        status.set(200);signed.set(false);answer.set("{\"allowed\":true,\"reason\":null}");
        assertFalse(asUser("metadata",()->client.check("alice","table",7L,"view",Map.of())).available());
        assertEquals(3,metrics.counter("aetheris.auth.permission.check.requests").count());
        assertEquals(2,metrics.counter("aetheris.auth.permission.check.unavailable").count());
    }
    @Test void batchCannotIntroduceUnrequestedResourcesAndVisibilityCannotInventAll() throws Exception {
        answer.set("{\"allowedIds\":[7]}");assertEquals(Set.of(7L),asUser("metadata",()->client.checkBatch("alice","table","view",Map.of(7L,Map.of()))));
        answer.set("{\"allowedIds\":[9]}");assertTrue(asUser("metadata",()->client.checkBatch("alice","table","view",Map.of(7L,Map.of()))).isEmpty());
        answer.set("{\"mode\":\"unknown\",\"ids\":[]}");assertEquals("OPAQUE",asUser("metadata",()->client.visibility("alice","table","view")).mode());
        answer.set("{\"mode\":\"IDS\",\"ids\":[7]}");assertEquals(List.of(7L),asUser("metadata",()->client.visibility("alice","table","view")).ids());
    }
}
