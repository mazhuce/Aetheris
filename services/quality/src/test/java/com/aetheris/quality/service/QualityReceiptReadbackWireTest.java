package com.aetheris.quality.service;

import com.aetheris.quality.fixedexecution.FixedJson;
import com.aetheris.quality.security.V2QualityFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real Ed25519 and loopback HTTP: receipt readback cannot dispatch another physical query. */
class QualityReceiptReadbackWireTest {
    private static final String HASH="a".repeat(64),STATUS_OPERATION="metadata.fixed-execution.status";

    private static final class Wire implements AutoCloseable {
        final V2QualityFixture fixture=new V2QualityFixture();
        final SignedHttpClient transport;
        final QualityQueryClient client;
        final AtomicInteger submits=new AtomicInteger(),statuses=new AtomicInteger();
        final List<ObjectNode> requests=new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> peerFailure=new AtomicReference<>();
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile String state="succeeded",mode="";
        volatile int httpStatus=200;
        volatile Consumer<ObjectNode> change=ignored->{};

        Wire() throws Exception {
            fixture.server.createContext("/internal/v2/executions/",exchange->{
                try {
                    String path=exchange.getRequestURI().getRawPath();
                    if(QualityQueryClient.SUBMIT.equals(path))submits.incrementAndGet();
                    else if(QualityQueryClient.STATUS.equals(path))statuses.incrementAndGet();
                    assertEquals(QualityQueryClient.STATUS,path,"Readback must never submit or use another route");
                    assertEquals("POST",exchange.getRequestMethod());
                    assertNull(exchange.getRequestURI().getRawQuery());
                    for(String header:List.of(SignedHttpClient.USER_PROOF,"X-Internal-Token","Authorization","Cookie","X-User","X-Username","X-Role"))
                        assertNull(exchange.getRequestHeaders().getFirst(header));
                    byte[] raw=exchange.getRequestBody().readNBytes(65537);
                    assertTrue(raw.length<=65536);
                    var proof=fixture.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                            new ExpectedRequest("metadata","POST",path,BodyHash.sha256(raw),STATUS_OPERATION,BodyHash.userProof(null)));
                    assertEquals("quality",proof.caller());
                    var request=(ObjectNode)FixedJson.parse(raw,65536);
                    FixedJson.fields(request,"receiptId","runId","attemptId","fence","operation");
                    assertEquals(11,FixedJson.number(request,"runId",1));
                    assertEquals(0,FixedJson.number(request,"attemptId",0));
                    assertEquals(1,FixedJson.number(request,"fence",1));
                    assertEquals(QualityQueryClient.OP,FixedJson.text(request,"operation"));
                    requests.add(request);
                    if(mode.equals("drop"))return;
                    if(mode.equals("interrupt")) {
                        received.countDown();
                        assertTrue(release.await(3,TimeUnit.SECONDS));
                        return;
                    }
                    var body=FixedJson.JSON.createObjectNode().put("receiptId",99).put("origin","quality")
                            .put("operation",QualityQueryClient.OP).put("runId",11).put("attemptId",0).put("fence",1).put("state",state);
                    body.putNull("result");body.putNull("errorCode");
                    if(state.equals("succeeded"))body.putObject("result").put("violationCount",12).put("durationMs",45);
                    if(state.equals("failed"))body.put("errorCode","QUALITY_QUERY_REJECTED");
                    change.accept(body);
                    byte[] bytes=FixedJson.bytes(body);
                    if(mode.equals("duplicate"))bytes=new String(bytes,StandardCharsets.UTF_8).replace("\"receiptId\":99","\"receiptId\":99,\"receiptId\":99").getBytes(StandardCharsets.UTF_8);
                    if(mode.equals("trailing"))bytes=(new String(bytes,StandardCharsets.UTF_8)+" {}").getBytes(StandardCharsets.UTF_8);
                    long now=fixture.clock.instant().getEpochSecond();
                    String nonce=mode.equals("wrong-nonce")?Nonce.random():proof.proof().nonce();
                    String token=fixture.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(
                            new WorkloadResponseProof(2,"metadata","quality",nonce,httpStatus,BodyHash.sha256(bytes),now,now+30));
                    if(mode.equals("forged")) {
                        int signatureStart=token.lastIndexOf('.')+1;
                        token=token.substring(0,signatureStart)+(token.charAt(signatureStart)=='A'?'B':'A')+token.substring(signatureStart+1);
                    }
                    if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,token);
                    if(mode.equals("tampered"))bytes="{}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(httpStatus,bytes.length);
                    exchange.getResponseBody().write(bytes);
                } catch(Throwable failure) {peerFailure.compareAndSet(null,failure);}
                finally {exchange.close();}
            });
            transport=new SignedHttpClient("quality",fixture.qualitySigner,fixture.verifier,fixture.clock,TimePolicy.defaults(),
                    Map.of("metadata",URI.create("http://127.0.0.1:"+fixture.server.getAddress().getPort())),
                    Map.of("metadata",Set.of(QualityQueryClient.OP,STATUS_OPERATION)),
                    new SignedHttpClient.Limits(65536,16384,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
            client=new QualityQueryClient(transport,Duration.ofSeconds(2),Duration.ofMillis(5));
        }

        QualityQueryClient.Submission source(){return new QualityQueryClient.Submission(11,2,HASH);}
        void assertSingleStatus(Long receipt) {
            assertEquals(0,submits.get());assertEquals(1,statuses.get());assertEquals(1,requests.size());
            if(receipt==null)assertTrue(requests.getFirst().get("receiptId").isNull());
            else assertEquals(receipt.longValue(),FixedJson.number(requests.getFirst(),"receiptId",1));
        }
        @Override public void close() throws Exception {
            release.countDown();transport.close();fixture.close();
            if(peerFailure.get()!=null)throw new AssertionError("Isolated metadata receipt peer failed",peerFailure.get());
        }
    }

    @Test void missingReceiptIdIsRecoveredByOneSignedStatusRequestWithoutSubmit() throws Exception {
        try(var wire=new Wire()) {
            var bindings=new ArrayList<Long>();
            assertEquals(new QualityQueryClient.QueryResult(12,45),wire.client.reconcile(wire.source(),null,bindings::add));
            assertEquals(List.of(99L),bindings);wire.assertSingleStatus(null);
        }
    }

    @Test void knownReceiptIdIsReadWithoutAnySubmitOrPollingLoop() throws Exception {
        try(var wire=new Wire()) {
            var bindings=new ArrayList<Long>();
            assertEquals(new QualityQueryClient.QueryResult(12,45),wire.client.reconcile(wire.source(),99L,bindings::add));
            assertEquals(List.of(99L),bindings);wire.assertSingleStatus(99L);
        }
    }

    @Test void pendingAndUnknownReceiptsBindTheOriginalIdAndRemainUnconfirmed() throws Exception {
        for(String state:List.of("queued","executing","unknown"))try(var wire=new Wire()) {
            wire.state=state;var bindings=new ArrayList<Long>();
            assertNull(wire.client.reconcile(wire.source(),null,bindings::add),state);
            assertEquals(List.of(99L),bindings);wire.assertSingleStatus(null);
        }
    }

    @Test void signed404LeavesBothLostAndKnownReceiptsUnconfirmedWithoutBinding() throws Exception {
        for(Long receipt:Arrays.asList(null,99L))try(var wire=new Wire()) {
            wire.httpStatus=404;var bindings=new ArrayList<Long>();
            assertNull(wire.client.reconcile(wire.source(),receipt,bindings::add));
            assertTrue(bindings.isEmpty());wire.assertSingleStatus(receipt);
        }
    }

    @Test void succeededReceiptPreservesZeroCountsAndFullLongDuration() throws Exception {
        try(var wire=new Wire()) {
            wire.change=body->{var result=(ObjectNode)body.get("result");result.put("violationCount",0);result.put("durationMs",Long.MAX_VALUE);};
            assertEquals(new QualityQueryClient.QueryResult(0,Long.MAX_VALUE),wire.client.reconcile(wire.source(),99L,id->assertEquals(99,id)));
            wire.assertSingleStatus(99L);
        }
    }

    @Test void failedReceiptBindsItsIdBeforeReportingAConfirmedFailure() throws Exception {
        for(Long receipt:Arrays.asList(null,99L))try(var wire=new Wire()) {
            wire.state="failed";var bindings=new ArrayList<Long>();
            var failure=assertThrows(QualityQueryClient.QualityExecutionException.class,()->wire.client.reconcile(wire.source(),receipt,bindings::add));
            assertFalse(failure.outcomeUnknown());assertEquals(List.of(99L),bindings);wire.assertSingleStatus(receipt);
        }
    }

    @Test void mismatchedSourceTupleOrKnownReceiptCannotBindOrComplete() throws Exception {
        Map<String,Consumer<ObjectNode>> changes=new LinkedHashMap<>();
        changes.put("receiptId",body->body.put("receiptId",100));
        changes.put("origin",body->body.put("origin","workflow"));
        changes.put("operation",body->body.put("operation","metadata.fixed-workflow-sql.submit"));
        changes.put("runId",body->body.put("runId",12));
        changes.put("attemptId",body->body.put("attemptId",1));
        changes.put("fence",body->body.put("fence",2));
        for(var change:changes.entrySet())try(var wire=new Wire()) {
            wire.change=change.getValue();var bindings=new ArrayList<Long>();
            var failure=assertThrows(QualityQueryClient.QualityExecutionException.class,()->wire.client.reconcile(wire.source(),99L,bindings::add),change.getKey());
            assertTrue(failure.outcomeUnknown());assertTrue(bindings.isEmpty());wire.assertSingleStatus(99L);
        }
    }

    @Test void malformedResultFieldsAndTypesNeverBecomeCompletionEvidence() throws Exception {
        List<Consumer<ObjectNode>> changes=List.of(
                body->body.remove("errorCode"),body->body.put("unexpected",true),body->body.put("receiptId","99"),
                body->((ObjectNode)body.get("result")).put("violationCount",1.5),
                body->((ObjectNode)body.get("result")).put("violationCount",-1),
                body->((ObjectNode)body.get("result")).put("durationMs",new java.math.BigInteger("9223372036854775808")),
                body->((ObjectNode)body.get("result")).putNull("durationMs"),
                body->body.put("errorCode","QUERY_FAILED"),body->body.put("state","passed"));
        for(var change:changes)try(var wire=new Wire()) {
            wire.change=change;var bindings=new ArrayList<Long>();
            assertThrows(RuntimeException.class,()->wire.client.reconcile(wire.source(),99L,bindings::add));
            assertTrue(bindings.isEmpty());wire.assertSingleStatus(99L);
        }
        for(String mode:List.of("duplicate","trailing"))try(var wire=new Wire()) {
            wire.mode=mode;var bindings=new ArrayList<Long>();
            assertThrows(RuntimeException.class,()->wire.client.reconcile(wire.source(),99L,bindings::add));
            assertTrue(bindings.isEmpty());wire.assertSingleStatus(99L);
        }
    }

    @Test void untrustedResponseProofOrWrongNonceCannotBindAnOriginalReceipt() throws Exception {
        for(String mode:List.of("unsigned","forged","wrong-nonce","tampered"))try(var wire=new Wire()) {
            wire.mode=mode;var bindings=new ArrayList<Long>();
            assertThrows(SignedHttpClient.TransportException.class,()->wire.client.reconcile(wire.source(),null,bindings::add),mode);
            assertTrue(bindings.isEmpty());wire.assertSingleStatus(null);
        }
    }

    @Test void signedHttpFailuresDoNotCauseFallbackSubmitOrAnotherStatusRequest() throws Exception {
        for(int status:List.of(400,401,403,409,500,503))try(var wire=new Wire()) {
            wire.httpStatus=status;var bindings=new ArrayList<Long>();
            var failure=assertThrows(QualityQueryClient.QualityExecutionException.class,()->wire.client.reconcile(wire.source(),99L,bindings::add));
            assertTrue(failure.outcomeUnknown());assertTrue(bindings.isEmpty());wire.assertSingleStatus(99L);
        }
    }

    @Test void bindingFailureStopsReadbackWithoutAnotherRequest() throws Exception {
        try(var wire=new Wire()) {
            var bindings=new AtomicInteger();
            assertThrows(IllegalStateException.class,()->wire.client.reconcile(wire.source(),null,id->{bindings.incrementAndGet();throw new IllegalStateException("Original source changed");}));
            assertEquals(1,bindings.get());wire.assertSingleStatus(null);
        }
    }

    @Test void lostStatusResponseNeverTriggersAReplacementExecution() throws Exception {
        try(var wire=new Wire()) {
            wire.mode="drop";var bindings=new ArrayList<Long>();
            assertThrows(SignedHttpClient.TransportException.class,()->wire.client.reconcile(wire.source(),null,bindings::add));
            assertTrue(bindings.isEmpty());wire.assertSingleStatus(null);
        }
    }

    @Test void interruptionPreservesTheFlagAndCannotBindALateReceiptOrReplay() throws Exception {
        try(var wire=new Wire()) {
            wire.mode="interrupt";var failure=new AtomicReference<Throwable>();
            var interrupted=new AtomicBoolean();var bindings=new AtomicInteger();
            Thread worker=Thread.ofPlatform().start(()->{
                try {wire.client.reconcile(wire.source(),null,id->bindings.incrementAndGet());}
                catch(Throwable expected) {failure.set(expected);interrupted.set(Thread.currentThread().isInterrupted());}
            });
            try {
                assertTrue(wire.received.await(2,TimeUnit.SECONDS));worker.interrupt();worker.join(2500);
                assertFalse(worker.isAlive());assertInstanceOf(QualityQueryClient.QualityExecutionException.class,failure.get());
                assertTrue(((QualityQueryClient.QualityExecutionException)failure.get()).outcomeUnknown());
                assertTrue(interrupted.get());assertEquals(0,bindings.get());wire.assertSingleStatus(null);
            } finally {wire.release.countDown();worker.interrupt();worker.join(1000);}
        }
    }
}
