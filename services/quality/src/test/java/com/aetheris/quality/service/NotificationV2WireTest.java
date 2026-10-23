package com.aetheris.quality.service;

import com.aetheris.quality.security.V2QualityFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class NotificationV2WireTest {
    static void await(BooleanSupplier ready) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!ready.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(ready.getAsBoolean());}
    static final class SystemFixture implements AutoCloseable {
        final V2QualityFixture keys;final HttpServer server;final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();final SignedHttpClient transport;
        final AtomicReference<Throwable> failure=new AtomicReference<>();final AtomicInteger calls=new AtomicInteger();
        final CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);volatile String fault="";
        SystemFixture(V2QualityFixture keys) throws Exception {
            this.keys=keys;var json=new ObjectMapper();var signer=keys.signer("system",KeyPurpose.WORKLOAD,"system");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
            server.createContext("/",exchange->{String mode=fault;try {
                byte[] raw=exchange.getRequestBody().readNBytes(16385);assertTrue(raw.length<=16384);
                assertEquals("/internal/v2/notifications",exchange.getRequestURI().getRawPath());assertNull(exchange.getRequestURI().getRawQuery());assertEquals("POST",exchange.getRequestMethod());
                for(String header:List.of(SignedHttpClient.USER_PROOF,"X-Internal-Token","X-Aetheris-Identity","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(header));
                var request=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("system","POST","/internal/v2/notifications",BodyHash.sha256(raw),"system.notification.record",BodyHash.userProof(null)));
                assertEquals("quality",request.caller());var body=json.readTree(raw);var fields=new HashSet<String>();body.fieldNames().forEachRemaining(fields::add);
                assertEquals(Set.of("recipient","source","sourceId","level","title","message","linkPath"),fields);
                assertEquals("quality",body.path("source").asText());assertEquals("admin",body.path("recipient").asText());assertEquals("/governance/quality",body.path("linkPath").asText());
                calls.incrementAndGet();if(mode.equals("hold")){entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}
                int status=mode.equals("signed500")?500:202;byte[] bytes=mode.equals("nonempty")?"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8):new byte[0];
                long now=keys.clock.instant().getEpochSecond();String proof=signer.sign(new WorkloadResponseProof(2,"system","quality",mode.equals("nonce")?Nonce.random():request.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof);
                if(mode.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);
            }catch(java.io.IOException cancelled){if(!mode.equals("hold"))failure.compareAndSet(null,cancelled);}
            catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}});server.start();
            transport=new SignedHttpClient("quality",keys.qualitySigner,keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("system",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),
                    Map.of("system",Set.of("system.notification.record")),new SignedHttpClient.Limits(16384,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
        }
        @Override public void close() throws Exception {
            release.countDown();transport.close();int port=server.getAddress().getPort();server.stop(0);executor.shutdownNow();assertTrue(executor.awaitTermination(3,TimeUnit.SECONDS));
            try(var socket=new Socket()){assertThrows(java.io.IOException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),300));}
            if(failure.get()!=null)throw new AssertionError("Local system replacement failed",failure.get());
        }
    }
    @Test void exactSevenFieldsAndSigned202EmptyAreRequiredWithoutUserOrRetries() throws Exception {
        try(var keys=new V2QualityFixture();var system=new SystemFixture(keys);var client=new NotificationClient(system.transport)) {
            client.notifyAdmin(8L,"critical","Quality failed","safe summary");await(()->client.stats().delivered()==1);
            for(String mode:List.of("unsigned","tampered","nonempty","nonce","signed500")){
                system.fault=mode;long before=client.stats().failed();client.notifyAdmin(8L,"warning","Test","safe summary");await(()->client.stats().failed()==before+1);
            }
            assertEquals(6,system.calls.get());assertEquals(1,client.stats().delivered());assertEquals(5,client.stats().failed());
            client.notifyAdmin(0L,"critical","Invalid",null);client.notifyAdmin(8L,"critical","X".repeat(257),null);client.notifyAdmin(8L,"critical","Invalid","X".repeat(1025));
            assertEquals(8,client.stats().failed());assertEquals(6,system.calls.get());
        }
    }
    @Test void queueIsBoundedAndShutdownCancelsOwnedRequestsWithoutDrainingIntoHttp() throws Exception {
        try(var keys=new V2QualityFixture();var system=new SystemFixture(keys)) {
            system.fault="hold";var client=new NotificationClient(system.transport);
            try {
                client.notifyAdmin(8L,"critical","Blocked","safe");client.notifyAdmin(8L,"critical","Blocked","safe");assertTrue(system.entered.await(1,TimeUnit.SECONDS));
                for(int i=0;i<50;i++)client.notifyAdmin(8L,"critical","Queued","safe");
                assertTrue(client.stats().queued()<=32);assertTrue(client.stats().dropped()>=18);
            }finally{client.close();}
            assertEquals(0,client.stats().queued());assertEquals(2,system.calls.get());assertEquals(2,client.stats().failed());
        }
    }
    @Test void twoSecondDeadlineBoundsStalledResponsesAndFailureHasNoRetry() throws Exception {
        try(var keys=new V2QualityFixture();var system=new SystemFixture(keys);var client=new NotificationClient(system.transport)) {
            system.fault="hold";long start=System.nanoTime();client.notifyAdmin(8L,"critical","Stalled","safe");await(()->client.stats().failed()==1);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3500);assertEquals(1,system.calls.get());assertEquals(0,client.stats().delivered());
        }
    }
}
