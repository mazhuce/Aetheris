package com.aetheris.aiagent.config;

import com.aetheris.aiagent.security.AiTestKeys;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real local auth replacement verifies the AI adapter, not auth's database/account implementation. */
class AiSessionWireTest {
    @Test void fixedSessionTupleHasNoUserHeaderAndUntrustedRepliesCannotAuthenticate() throws Exception {
        var keys=new AiTestKeys();var fault=new AtomicReference<>("");var count=new AtomicInteger();var failure=new AtomicReference<Throwable>();
        var json=new ObjectMapper();var executor=Executors.newVirtualThreadPerTaskExecutor();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.createContext("/",exchange->{try {
            byte[] raw=exchange.getRequestBody().readNBytes(16385);assertTrue(raw.length<=16384);
            assertEquals("/internal/v2/sessions/check",exchange.getRequestURI().getRawPath());assertNull(exchange.getRequestURI().getRawQuery());
            assertNull(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));assertNull(exchange.getRequestHeaders().getFirst("Authorization"));assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            var request=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                    new ExpectedRequest("auth","POST","/internal/v2/sessions/check",BodyHash.sha256(raw),"auth.session.check",BodyHash.userProof(null)));
            assertEquals("ai-agent",request.caller());var body=json.readTree(raw);assertEquals(Set.of("uid","username","accessVersion"),json.convertValue(body,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}).keySet());
            assertEquals(7,body.path("uid").asLong());assertEquals("alice",body.path("username").asText());assertEquals(3,body.path("accessVersion").asLong());count.incrementAndGet();
            byte[] reply=json.writeValueAsBytes(Map.of("active",!fault.get().equals("disabled")));long now=keys.clock.instant().getEpochSecond();
            String proof=keys.auth.sign(new WorkloadResponseProof(2,"auth","ai-agent",request.proof().nonce(),200,BodyHash.sha256(reply),now,now+30));
            if(!fault.get().equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof);
            if(fault.get().equals("tampered"))reply="{\"active\":false}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}});server.start();
        int port=server.getAddress().getPort();
        try(var transport=new SignedHttpClient("ai-agent",keys.ai,keys.verifier,keys.clock,TimePolicy.defaults(),
                Map.of("auth",URI.create("http://127.0.0.1:"+port)),Map.of("auth",Set.of("auth.session.check")),
                new SignedHttpClient.Limits(16384,16384,16,Duration.ofMillis(500),Duration.ofSeconds(2)))) {
            var sessions=new AiV2SecurityConfiguration().aiSessionValidator(transport);
            assertTrue(sessions.isActive(7,"alice",3));fault.set("disabled");assertFalse(sessions.isActive(7,"alice",3));
            for(String mode:List.of("unsigned","tampered")){fault.set(mode);assertThrows(Exception.class,()->sessions.isActive(7,"alice",3));}
            assertEquals(4,count.get());
        }finally {
            server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
            try(var socket=new Socket()){assertThrows(java.io.IOException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),300));}
        }
        if(failure.get()!=null)throw new AssertionError("Auth replacement failed",failure.get());
    }
}
