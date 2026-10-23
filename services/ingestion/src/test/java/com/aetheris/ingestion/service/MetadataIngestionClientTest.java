package com.aetheris.ingestion.service;

import com.aetheris.ingestion.fixedexecution.FixedJson;
import com.aetheris.ingestion.security.V2IngestionFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MetadataIngestionClientTest {
    static final String HASH="a".repeat(64);
    static class Wire implements AutoCloseable {
        final V2IngestionFixture f=new V2IngestionFixture();final SignedHttpClient transport;final MetadataIngestionClient client;
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger submits=new AtomicInteger(),statuses=new AtomicInteger();final List<ObjectNode> requests=new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();volatile String submitState="queued",statusState="succeeded",mode="";volatile long receipt=99;
        Wire(Duration timeout) throws Exception {
            f.server.createContext("/internal/v2/executions/",exchange->{
                boolean submit=exchange.getRequestURI().getRawPath().equals(MetadataIngestionClient.SUBMIT);
                try {
                    if(submit)submits.incrementAndGet();else statuses.incrementAndGet();byte[] raw=exchange.getRequestBody().readNBytes(65537);
                    String op=submit?MetadataIngestionClient.OP:"metadata.fixed-execution.status";assertNull(exchange.getRequestURI().getRawQuery());
                    assertNull(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                    var proof=f.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata",exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),BodyHash.sha256(raw),op,BodyHash.userProof(null)));
                    assertEquals("ingestion",proof.caller());var request=(ObjectNode)FixedJson.parse(raw,65536);requests.add(request);
                    if(submit){FixedJson.fields(request,"runId","attemptId","fence","executionRevision","manifestSha256");assertEquals(HASH,request.path("manifestSha256").asText());}
                    else FixedJson.fields(request,"receiptId","runId","attemptId","fence","operation");
                    if(submit&&mode.equals("drop"))return;
                    if(submit&&mode.equals("cancel")){received.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return;}
                    if(submit&&mode.equals("delay"))Thread.sleep(150);
                    String state=submit?submitState:statusState;
                    var response=FixedJson.JSON.createObjectNode().put("receiptId",!submit&&mode.equals("different-receipt")?100:receipt).put("origin","ingestion").put("operation",MetadataIngestionClient.OP).put("runId",11).put("attemptId",0).put("fence",1).put("state",state);
                    response.putNull("result");response.putNull("errorCode");if(state.equals("failed"))response.put("errorCode","TRANSFER_REJECTED");
                    if(state.equals("succeeded"))response.putObject("result").put("rowsRead",12).put("rowsWritten",12).put("durationMs",45);
                    if(mode.equals("decimal"))((ObjectNode)response.get("result")).put("rowsRead",1.5);
                    if(mode.equals("negative"))((ObjectNode)response.get("result")).put("rowsWritten",-1);
                    if(mode.equals("overflow"))((ObjectNode)response.get("result")).put("durationMs",new java.math.BigInteger("9223372036854775808"));
                    if(mode.equals("missing"))response.remove("errorCode");if(mode.equals("extra"))response.put("sql","SELECT 1");if(mode.equals("binding"))response.put("runId",12);
                    byte[] bytes=FixedJson.bytes(response);int status=submit?202:200;long now=f.clock.instant().getEpochSecond();
                    String token=f.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(new WorkloadResponseProof(2,"metadata","ingestion",proof.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                    if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,token);
                    if(mode.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
            });
            transport=new SignedHttpClient("ingestion",f.ingestionSigner,f.verifier,f.clock,TimePolicy.defaults(),Map.of("metadata",URI.create("http://127.0.0.1:"+f.server.getAddress().getPort())),Map.of("metadata",Set.of(MetadataIngestionClient.OP,"metadata.fixed-execution.status")),new SignedHttpClient.Limits(65536,16384,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
            client=new MetadataIngestionClient(transport,timeout,Duration.ofMillis(5));
        }
        MetadataIngestionClient.Submission key(){return new MetadataIngestionClient.Submission(11,2,HASH);}
        public void close() throws Exception {release.countDown();transport.close();f.close();if(failure.get()!=null)throw new AssertionError("Metadata replacement failed",failure.get());}
    }
    @Test void bindsOnlyOneReceiptAndPollsStatusUntilStrictTransferCounts() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {
            List<Long> bindings=new ArrayList<>();var result=w.client.execute(w.key(),bindings::add);assertEquals(new MetadataIngestionClient.TransferResult(12,12,45),result);
            assertEquals(List.of(99L),bindings);assertEquals(1,w.submits.get());assertEquals(1,w.statuses.get());assertEquals(99,w.requests.getLast().path("receiptId").asLong());
        }
    }
    @Test void lostSubmitResponseUsesStatusWithNullReceiptAndNeverResubmits() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {
            w.mode="drop";List<Long> bindings=new ArrayList<>();assertEquals(12,w.client.execute(w.key(),bindings::add).rowsWritten());
            assertEquals(1,w.submits.get());assertEquals(1,w.statuses.get());assertTrue(w.requests.getLast().get("receiptId").isNull());assertEquals(List.of(99L),bindings);
        }
    }
    @Test void validationFailureReceiptDiffersFromUnknownAndUntrustedOrMalformedSuccess() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {w.submitState="failed";assertFalse(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(0,w.statuses.get());}
        for(String mode:List.of("unsigned","tampered","decimal","negative","overflow","missing","extra","binding"))try(var w=new Wire(Duration.ofSeconds(2))) {
            w.submitState="succeeded";w.mode=mode;assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(1,w.submits.get());assertEquals(0,w.statuses.get());
        }
    }
    @Test void queuedUnknownTimeoutAndLateSuccessRetainUnknownOutcome() throws Exception {
        try(var w=new Wire(Duration.ofMillis(60))){w.statusState="queued";assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(1,w.submits.get());}
        try(var w=new Wire(Duration.ofSeconds(2))){w.submitState="unknown";assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(0,w.statuses.get());}
        try(var w=new Wire(Duration.ofMillis(50))){w.submitState="succeeded";w.mode="delay";AtomicInteger bound=new AtomicInteger();assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->bound.incrementAndGet())).outcomeUnknown());assertEquals(0,bound.get());}
    }
    @Test void changedReceiptOrBindingCallbackFailureCannotBecomeSuccess() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){w.mode="different-receipt";List<Long> bound=new ArrayList<>();assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),bound::add)).outcomeUnknown());assertEquals(List.of(99L),bound);assertEquals(1,w.submits.get());}
        try(var w=new Wire(Duration.ofSeconds(2))){assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->{throw new IllegalStateException();})).outcomeUnknown());assertEquals(0,w.statuses.get());}
    }
    @Test void alreadyCancelledThreadNeverStartsANewRequest() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){Thread.currentThread().interrupt();try{assertTrue(assertThrows(MetadataIngestionClient.IngestionExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,w.submits.get());}finally{Thread.interrupted();}}
    }
    @Test void interruptionWhileSubmitIsInFlightStopsStatusAndNeverRetries() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {
            w.mode="cancel";AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
            Thread worker=Thread.ofPlatform().start(()->{try{w.client.execute(w.key(),id->{throw new AssertionError("No late receipt binding");});}
                catch(Throwable expected){failure.set(expected);interrupted.set(Thread.currentThread().isInterrupted());}});
            try {
                assertTrue(w.received.await(2,TimeUnit.SECONDS));worker.interrupt();worker.join(2500);
                assertFalse(worker.isAlive());assertInstanceOf(MetadataIngestionClient.IngestionExecutionException.class,failure.get());
                assertTrue(((MetadataIngestionClient.IngestionExecutionException)failure.get()).outcomeUnknown());assertTrue(interrupted.get());
                assertEquals(1,w.submits.get());assertEquals(0,w.statuses.get());
            }finally{w.release.countDown();worker.interrupt();worker.join(1000);}
        }
    }

}
