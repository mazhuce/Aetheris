package com.aetheris.ingestion.security;

import com.aetheris.ingestion.service.NotificationClient;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real JDK HTTP and signed responses from a local system replacement; no real system or DB. */
class NotificationClientWireTest {
    static final String PATH="/internal/v2/notifications",OP="system.notification.record";
    static final ObjectMapper JSON=new ObjectMapper();
    static final class Wire implements AutoCloseable {
        final V2IngestionFixture f=new V2IngestionFixture();final SignedHttpClient transport;final NotificationClient notifications;
        final List<JsonNode> observed=new CopyOnWriteArrayList<>();final AtomicReference<Throwable> failure=new AtomicReference<>();
        final AtomicInteger requests=new AtomicInteger();volatile String fault="";volatile CountDownLatch block;
        Wire() throws Exception {
            f.server.createContext(PATH,exchange->{
                try {
                    requests.incrementAndGet();byte[] raw=exchange.getRequestBody().readNBytes(16385);assertTrue(raw.length<=16384);
                    assertEquals(PATH,exchange.getRequestURI().toASCIIString());assertNull(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));
                    for(String legacy:List.of("X-Internal-Token","X-Aetheris-Identity","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(legacy));
                    var request=f.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                            new ExpectedRequest("system",exchange.getRequestMethod(),PATH,BodyHash.sha256(raw),OP,BodyHash.userProof(null)));
                    assertEquals("ingestion",request.caller());var body=JSON.readTree(raw);
                    Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
                    assertEquals(Set.of("recipient","source","sourceId","level","title","message","linkPath"),fields);
                    assertEquals("ingestion",body.path("source").asText());assertEquals("admin",body.path("recipient").asText());
                    assertEquals("/integration/sources",body.path("linkPath").asText());assertTrue(body.path("title").asText().length()<=256);
                    assertTrue(body.path("message").asText().length()<=1024);assertTrue(Set.of("info","warning","critical").contains(body.path("level").asText()));
                    observed.add(body);if(block!=null)block.await(3,TimeUnit.SECONDS);
                    String mode=fault;int status=mode.equals("signed503")?503:mode.equals("wrongStatus")?200:202;
                    byte[] bytes=mode.equals("body")?"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8):new byte[0];long now=f.clock.instant().getEpochSecond();
                    String proof=f.signer("system",KeyPurpose.WORKLOAD,"system").sign(new WorkloadResponseProof(2,"system",mode.equals("receiver")?"workflow":"ingestion",request.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                    if(mode.equals("purpose"))proof=f.token();if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,proof);
                    if(mode.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)exchange.getResponseBody().write(bytes);
                }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
            });
            transport=new SignedHttpClient("ingestion",f.ingestionSigner,f.verifier,f.clock,TimePolicy.defaults(),
                    Map.of("system",URI.create("http://127.0.0.1:"+f.server.getAddress().getPort())),Map.of("system",Set.of(OP)),
                    new SignedHttpClient.Limits(16384,1024,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
            notifications=new NotificationClient(transport);
        }
        void waitFor(long finished) throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
            while(System.nanoTime()<deadline){var stats=notifications.stats();if(stats.delivered()+stats.failed()+stats.dropped()>=finished)return;Thread.sleep(10);}
            fail("Notification task did not finish within its bound");
        }
        public void close() throws Exception {if(block!=null)block.countDown();notifications.close();transport.close();f.close();if(failure.get()!=null)throw new AssertionError("System replacement failure",failure.get());}
    }
    @Test void validSystem202EmptyBodyRequiresExactWorkloadAndNoUser() throws Exception {
        try(var wire=new Wire()){
            wire.notifications.notifyAdmin(8L,"critical","failure","safe details");wire.waitFor(1);
            assertEquals(1,wire.notifications.stats().delivered());assertEquals(1,wire.requests.get());assertEquals(8,wire.observed.getFirst().path("sourceId").asLong());
            assertThrows(RuntimeException.class,()->wire.transport.send(new SignedHttpClient.Request("system","POST",PATH,"system.audit.record",new byte[0])));
            assertEquals(1,wire.requests.get());
        }
    }
    @Test void unsignedTamperedWrongPurposeReceiverStatusAndBodyCannotCountAsDelivery() throws Exception {
        try(var wire=new Wire()){
            long count=0;
            for(String mode:List.of("unsigned","tampered","purpose","receiver","wrongStatus","body","signed503")){
                wire.fault=mode;wire.notifications.notifyAdmin(8L,"warning","failure","details");wire.waitFor(++count);
                assertEquals(0,wire.notifications.stats().delivered());assertEquals(count,wire.notifications.stats().failed());assertEquals(count,wire.requests.get());
            }
        }
    }
    @Test void invalidSystemDtoFieldsFailLocallyAndNeverTriggerAnyExecutionOrHttp() throws Exception {
        try(var wire=new Wire()){
            wire.notifications.notifyAdmin(0L,"critical","title","message");wire.notifications.notifyAdmin(8L,"success","title","message");
            wire.notifications.notifyAdmin(8L,"critical","x".repeat(257),"message");wire.notifications.notifyAdmin(8L,"critical","title","x".repeat(1025));
            assertEquals(4,wire.notifications.stats().failed());assertEquals(0,wire.requests.get());
        }
    }
    @Test void actualWorkerQueueAndShutdownAreBounded() throws Exception {
        try(var wire=new Wire()){
            wire.block=new CountDownLatch(1);
            for(int i=0;i<100;i++)wire.notifications.notifyAdmin(8L,"critical","title","details");
            assertTrue(wire.notifications.stats().queued()<=32);assertTrue(wire.notifications.stats().dropped()>0);
            wire.block.countDown();wire.waitFor(100);wire.notifications.close();
            long before=wire.notifications.stats().dropped();wire.notifications.notifyAdmin(8L,"critical","title","details");
            assertEquals(before+1,wire.notifications.stats().dropped());
        }
    }
}
