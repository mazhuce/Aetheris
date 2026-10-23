package com.aetheris.auth.audit;

import com.aetheris.auth.security.V2ProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback HTTP and Ed25519 request/response proofs; no running platform service. */
class PermissionAuditClientWireTest {
    final V2ProofFixture proof=new V2ProofFixture();
    HttpServer server;ExecutorService serverThreads;SignedHttpClient transport;PermissionAuditClient client;
    final AtomicInteger requests=new AtomicInteger();final AtomicReference<Throwable> handlerFailure=new AtomicReference<>();
    final AtomicReference<String> mode=new AtomicReference<>("good");
    final CountDownLatch received=new CountDownLatch(2),release=new CountDownLatch(1);
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        serverThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(serverThreads);
        server.createContext("/internal/v2/audit/permission-events",exchange->{
            try {
                byte[] raw=exchange.getRequestBody().readNBytes(16385);assertTrue(raw.length<=16384);
                assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getRawQuery());
                assertNull(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));
                assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                var request=proof.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest("system","POST","/internal/v2/audit/permission-events",BodyHash.sha256(raw),"system.permission-event.record",BodyHash.userProof(null)));
                assertEquals("auth",request.caller());assertTrue(new String(raw,java.nio.charset.StandardCharsets.UTF_8).contains("alice"));
                requests.incrementAndGet();received.countDown();
                String selected=mode.get();if(selected.equals("block"))release.await(3,TimeUnit.SECONDS);
                byte[] body=selected.equals("body")?new byte[]{1}:new byte[0];
                int status=selected.equals("wrong-status")?500:selected.equals("signed-error")?503:202;
                long now=proof.clock.instant().getEpochSecond();
                if(!selected.equals("unsigned")) {
                    String signature=proof.system.sign(new WorkloadResponseProof(2,"system","auth",request.proof().nonce(),
                            selected.equals("wrong-status")?202:status,BodyHash.sha256(body),now,now+30));
                    if(selected.equals("tampered"))signature=signature.substring(0,signature.length()-8)+"AAAAAAAA";
                    exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,signature);
                }
                exchange.sendResponseHeaders(status,body.length==0?-1:body.length);
                if(body.length>0)exchange.getResponseBody().write(body);
            }catch(Throwable failure){if(!(mode.get().equals("block") && failure instanceof java.io.IOException))handlerFailure.compareAndSet(null,failure);}
            finally{exchange.close();}
        });server.start();
        transport=new SignedHttpClient("auth",proof.auth,proof.verifier,proof.clock,TimePolicy.defaults(),
                Map.of("system",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),Map.of("system",Set.of("system.permission-event.record")),
                new SignedHttpClient.Limits(16*1024,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
        client=new PermissionAuditClient(transport);
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown();
        if(client!=null)client.close();if(transport!=null)transport.close();if(server!=null)server.stop(0);
        if(serverThreads!=null){serverThreads.shutdownNow();assertTrue(serverThreads.awaitTermination(3,TimeUnit.SECONDS));}
        if(handlerFailure.get()!=null)throw new AssertionError("Test HTTP receiver failed",handlerFailure.get());
    }
    PermissionEventRequest event(){return new PermissionEventRequest("alice",42L,"table","7","query","DENIED","revoked","audit-test");}
    @Test void signed202EmptyAcknowledgementIsTheOnlySuccessfulDelivery() {
        client.record(event());assertEquals(1,requests.get());assertEquals(1,client.deliveryStats().delivered());assertEquals(0,client.deliveryStats().failed());
    }
    @Test void missingTamperedWrongStatusSignedErrorAndNonEmptyAckRemainObservableFailures() {
        for(String selected:List.of("unsigned","tampered","wrong-status","signed-error","body")){mode.set(selected);client.record(event());}
        assertEquals(5,requests.get());assertEquals(0,client.deliveryStats().delivered());assertEquals(5,client.deliveryStats().failed());
    }
    @Test void oversizedOrMissingEventHasZeroNetworkSideEffects() {
        client.record(null);var event=event();event.setReason("x".repeat(17000));client.record(event);
        assertEquals(0,requests.get());assertEquals(2,client.deliveryStats().failed());
    }
    @Test void asynchronousQueueAndShutdownAreBoundedAndDoNotThrowToDecisionCaller() throws Exception {
        mode.set("block");client.recordAsync(event(),Duration.ofSeconds(2));client.recordAsync(event(),Duration.ofSeconds(2));
        assertTrue(received.await(2,TimeUnit.SECONDS));
        for(int i=0;i<70;i++)client.recordAsync(event(),Duration.ofSeconds(2));
        assertTrue(client.deliveryStats().queued()<=64);assertTrue(client.deliveryStats().dropped()>=6);
        client.close();assertEquals(0,client.deliveryStats().queued());
        client.recordAsync(event(),Duration.ofSeconds(2));assertTrue(client.deliveryStats().dropped()>=71);
    }
}
