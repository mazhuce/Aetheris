package com.aetheris.workflow.service;

import com.aetheris.workflow.fixedexecution.FixedJson;
import com.aetheris.workflow.security.V2WorkflowFixture;
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

class SqlExecutionClientWireTest {
    static final String HASH="a".repeat(64);
    static class Wire implements AutoCloseable {
        final V2WorkflowFixture f=new V2WorkflowFixture();final SignedHttpClient transport;final SqlExecutionClient client;
        final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger submits=new AtomicInteger(),statuses=new AtomicInteger();final List<ObjectNode> requests=new CopyOnWriteArrayList<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();volatile String submitState="queued",statusState="succeeded",mode="";volatile long receipt=99;volatile Runnable responseHook=()->{};
        Wire(Duration timeout) throws Exception {
            f.server.createContext("/internal/v2/executions/",exchange->{
                boolean submit=exchange.getRequestURI().getRawPath().equals(SqlExecutionClient.SUBMIT);
                try {
                    if(submit)submits.incrementAndGet();else statuses.incrementAndGet();byte[] raw=exchange.getRequestBody().readNBytes(65537);
                    String op=submit?SqlExecutionClient.OP:"metadata.fixed-execution.status";assertNull(exchange.getRequestURI().getRawQuery());
                    assertNull(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                    var proof=f.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata",exchange.getRequestMethod(),exchange.getRequestURI().getRawPath(),BodyHash.sha256(raw),op,BodyHash.userProof(null)));
                    assertEquals("workflow",proof.caller());var request=(ObjectNode)FixedJson.parse(raw,65536);requests.add(request);
                    if(submit){FixedJson.fields(request,"runId","attemptId","fence","executionRevision","manifestSha256");assertEquals(HASH,request.path("manifestSha256").asText());}
                    else FixedJson.fields(request,"receiptId","runId","attemptId","fence","operation");
                    if(submit&&mode.equals("drop"))return;
                    if(submit&&mode.equals("cancel")){received.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return;}
                    if(submit&&mode.equals("delay"))Thread.sleep(150);
                    String state=submit?submitState:statusState;
                    var response=FixedJson.JSON.createObjectNode().put("receiptId",!submit&&mode.equals("different-receipt")?100:receipt).put("origin","workflow").put("operation",SqlExecutionClient.OP).put("runId",11).put("attemptId",22).put("fence",3).put("state",state);
                    response.putNull("result");response.putNull("errorCode");if(state.equals("failed"))response.put("errorCode","TRANSFER_REJECTED");
                    if(state.equals("succeeded"))response.putObject("result").put("affectedRows",12).put("durationMs",45);
                    if(mode.equals("null-count"))((ObjectNode)response.get("result")).putNull("affectedRows");
                    if(mode.equals("decimal"))((ObjectNode)response.get("result")).put("affectedRows",1.5);
                    if(mode.equals("negative"))((ObjectNode)response.get("result")).put("affectedRows",-1);
                    if(mode.equals("overflow"))((ObjectNode)response.get("result")).put("durationMs",new java.math.BigInteger("9223372036854775808"));
                    if(mode.equals("missing"))response.remove("errorCode");if(mode.equals("extra"))response.put("sql","SELECT 1");if(mode.equals("binding"))response.put("runId",12);
                    byte[] bytes=FixedJson.bytes(response);int status=submit?202:200;long now=f.clock.instant().getEpochSecond();
                    String token=f.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(new WorkloadResponseProof(2,"metadata","workflow",proof.proof().nonce(),status,BodyHash.sha256(bytes),now,now+30));
                    if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,token);
                    if(mode.equals("tampered"))bytes="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    responseHook.run();exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
            });
            f.server.createContext("/internal/v2/sql/validate-write",exchange->{
                try{
                    byte[] raw=exchange.getRequestBody().readNBytes(65537);assertNull(exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));
                    var proof=f.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("metadata","POST","/internal/v2/sql/validate-write",BodyHash.sha256(raw),"metadata.fixed-sql.validate-write",BodyHash.userProof(null)));
                    var request=FixedJson.parse(raw,65536);FixedJson.fields(request,"sql");assertEquals("workflow",proof.caller());
                    var response=FixedJson.JSON.createObjectNode().put("valid",!mode.equals("reject"));response.putNull("reason");if(mode.equals("reject"))response.put("reason","SQL_POLICY_REJECTED");if(mode.equals("invalid-reason"))response.put("reason","arbitrary diagnostic");
                    byte[] bytes=FixedJson.bytes(response);long now=f.clock.instant().getEpochSecond();
                    var token=f.signer("metadata",KeyPurpose.WORKLOAD,"metadata").sign(new WorkloadResponseProof(2,"metadata","workflow",proof.proof().nonce(),200,BodyHash.sha256(bytes),now,now+30));
                    if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,token);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}
            });
            transport=new SignedHttpClient("workflow",f.workflowSigner,f.verifier,f.clock,TimePolicy.defaults(),Map.of("metadata",URI.create("http://127.0.0.1:"+f.server.getAddress().getPort())),Map.of("metadata",Set.of(SqlExecutionClient.OP,"metadata.fixed-execution.status","metadata.fixed-sql.validate-write")),new SignedHttpClient.Limits(65536,16384,16,Duration.ofMillis(500),Duration.ofSeconds(2)));
            client=new SqlExecutionClient(transport,timeout,Duration.ofMillis(5));
        }
        SqlExecutionClient.Submission key(){return new SqlExecutionClient.Submission(11,22,3,2,HASH);}
        public void close() throws Exception {release.countDown();transport.close();f.close();if(failure.get()!=null)throw new AssertionError("Metadata replacement failed",failure.get());}
    }
    @Test void validationUsesOnlyFixedSignedPurePolicyRouteAndStrictReason() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){assertNull(w.client.validateWrite("INSERT INTO t SELECT 1"));w.mode="reject";assertNotNull(w.client.validateWrite("DROP TABLE t"));assertEquals(400,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->w.client.requireWritePolicy("DROP TABLE t")).getStatusCode().value());assertEquals(0,w.submits.get());assertEquals(0,w.statuses.get());}
        for(String mode:List.of("unsigned","invalid-reason"))try(var w=new Wire(Duration.ofSeconds(2))){w.mode=mode;assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->w.client.requireWritePolicy("INSERT INTO t SELECT 1")).getStatusCode().value());}
    }
    @Test void signedNullableRowCountPreservesUnknownRatherThanZero() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){w.mode="null-count";w.submitState="succeeded";assertNull(w.client.execute(w.key(),id->{}).affectedRows());}
    }
    @Test void bindsOnlyOneReceiptAndPollsStatusUntilStrictTransferCounts() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {
            List<Long> bindings=new ArrayList<>();var result=w.client.execute(w.key(),bindings::add);assertEquals(new SqlExecutionClient.ExecutionResult(12L,45),result);
            assertEquals(List.of(99L),bindings);assertEquals(1,w.submits.get());assertEquals(1,w.statuses.get());assertEquals(99,w.requests.getLast().path("receiptId").asLong());
        }
    }
    @Test void lostSubmitResponseUsesStatusWithNullReceiptAndNeverResubmits() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {
            w.mode="drop";List<Long> bindings=new ArrayList<>();assertEquals(12,w.client.execute(w.key(),bindings::add).affectedRows());
            assertEquals(1,w.submits.get());assertEquals(1,w.statuses.get());assertTrue(w.requests.getLast().get("receiptId").isNull());assertEquals(List.of(99L),bindings);
        }
    }
    @Test void validationFailureReceiptDiffersFromUnknownAndUntrustedOrMalformedSuccess() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {w.submitState="failed";assertFalse(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(0,w.statuses.get());}
        for(String mode:List.of("unsigned","tampered","decimal","negative","overflow","missing","extra","binding"))try(var w=new Wire(Duration.ofSeconds(2))) {
            w.submitState="succeeded";w.mode=mode;assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(1,w.submits.get());assertEquals(0,w.statuses.get());
        }
    }
    @Test void queuedUnknownTimeoutAndLateSuccessRetainUnknownOutcome() throws Exception {
        try(var w=new Wire(Duration.ofMillis(60))){w.statusState="queued";assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(1,w.submits.get());}
        try(var w=new Wire(Duration.ofSeconds(2))){w.submitState="unknown";assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertEquals(0,w.statuses.get());}
        try(var w=new Wire(Duration.ofMillis(50))){w.submitState="succeeded";w.mode="delay";AtomicInteger bound=new AtomicInteger();assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->bound.incrementAndGet())).outcomeUnknown());assertEquals(0,bound.get());}
    }
    @Test void changedReceiptOrBindingCallbackFailureCannotBecomeSuccess() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){w.mode="different-receipt";List<Long> bound=new ArrayList<>();assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),bound::add)).outcomeUnknown());assertEquals(List.of(99L),bound);assertEquals(1,w.submits.get());}
        try(var w=new Wire(Duration.ofSeconds(2))){assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->{throw new IllegalStateException();})).outcomeUnknown());assertEquals(0,w.statuses.get());}
    }
    @Test void alreadyCancelledThreadNeverStartsANewRequest() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){Thread.currentThread().interrupt();try{assertTrue(assertThrows(SqlExecutionException.class,()->w.client.execute(w.key(),id->{})).outcomeUnknown());assertTrue(Thread.currentThread().isInterrupted());assertEquals(0,w.submits.get());}finally{Thread.interrupted();}}
    }
    @Test void interruptionWhileSubmitIsInFlightStopsStatusAndNeverRetries() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))) {
            w.mode="cancel";AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean interrupted=new AtomicBoolean();
            Thread worker=Thread.ofPlatform().start(()->{try{w.client.execute(w.key(),id->{throw new AssertionError("No late receipt binding");});}
                catch(Throwable expected){failure.set(expected);interrupted.set(Thread.currentThread().isInterrupted());}});
            try {
                assertTrue(w.received.await(2,TimeUnit.SECONDS));worker.interrupt();worker.join(2500);
                assertFalse(worker.isAlive());assertInstanceOf(SqlExecutionException.class,failure.get());
                assertTrue(((SqlExecutionException)failure.get()).outcomeUnknown());assertTrue(interrupted.get());
                assertEquals(1,w.submits.get());assertEquals(0,w.statuses.get());
            }finally{w.release.countDown();worker.interrupt();worker.join(1000);}
        }
    }

    static final class BudgetClock extends java.time.Clock {
        final AtomicReference<java.time.Instant> now=new AtomicReference<>(java.time.Instant.parse("2026-10-18T00:00:00Z"));
        final AtomicLong ticks=new AtomicLong();
        public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        public java.time.Clock withZone(java.time.ZoneId zone){return java.time.Clock.fixed(instant(),zone);}
        public java.time.Instant instant(){return now.get();}
        void advance(Duration value){now.updateAndGet(i->i.plus(value));ticks.addAndGet(value.toNanos());}
        java.time.OffsetDateTime lease(int timeout){return java.time.OffsetDateTime.ofInstant(instant().plusSeconds(timeout+60L),java.time.ZoneOffset.UTC);}
    }
    @Test void sealedDefaultAndMaximumBudgetsOutliveTheCompatibilityWaitWithoutTwentyFourHourSleep() throws Exception {
        for(int timeout:new int[]{1800,86400})try(var w=new Wire(Duration.ofMillis(1))){
            var time=new BudgetClock();var lease=time.lease(timeout);var once=new AtomicBoolean();
            w.responseHook=()->{if(once.compareAndSet(false,true))time.advance(Duration.ofSeconds(timeout==1800?1801:3601));};
            var client=new SqlExecutionClient(w.transport,Duration.ofMillis(1),Duration.ofMillis(1),time,time.ticks::get);
            assertEquals(12L,client.execute(w.key(),lease,id->{}).affectedRows());
            assertEquals(1,w.submits.get());assertEquals(1,w.statuses.get());
        }
    }
    @Test void lateSignedSuccessOrFailureCannotBindReceiptOrStartStatusAfterSourceReserve() throws Exception {
        for(String state:List.of("succeeded","failed","queued"))try(var w=new Wire(Duration.ofSeconds(2))){
            var time=new BudgetClock();var lease=time.lease(1);w.submitState=state;w.responseHook=()->time.advance(Duration.ofSeconds(56));
            var client=new SqlExecutionClient(w.transport,Duration.ofSeconds(2),Duration.ofMillis(1),time,time.ticks::get);var bindings=new AtomicInteger();
            assertTrue(assertThrows(SqlExecutionException.class,()->client.execute(w.key(),lease,id->bindings.incrementAndGet())).outcomeUnknown());
            assertEquals(0,bindings.get());assertEquals(1,w.submits.get());assertEquals(0,w.statuses.get());
        }
    }
    @Test void wallClockCannotExtendMonotonicBudgetAndForwardClockStopsLateResponses() throws Exception {
        for(boolean monotonicExpired:new boolean[]{true,false})try(var w=new Wire(Duration.ofSeconds(2))){
            var time=new BudgetClock();var lease=time.lease(1);w.submitState="succeeded";
            w.responseHook=()->{if(monotonicExpired){time.ticks.addAndGet(Duration.ofSeconds(56).toNanos());time.now.updateAndGet(i->i.minusSeconds(3600));}else time.now.updateAndGet(i->i.plusSeconds(56));};
            var client=new SqlExecutionClient(w.transport,Duration.ofSeconds(2),Duration.ofMillis(1),time,time.ticks::get);
            assertTrue(assertThrows(SqlExecutionException.class,()->client.execute(w.key(),lease,id->fail("late receipt"))).outcomeUnknown());
            assertEquals(1,w.submits.get());assertEquals(0,w.statuses.get());
        }
    }
    @Test void exhaustedMissingOrOverflowingSourceLeaseAndInterruptedCallerNeverSubmit() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){
            var time=new BudgetClock();var client=new SqlExecutionClient(w.transport,Duration.ofSeconds(2),Duration.ofMillis(1),time,time.ticks::get);
            for(var lease:Arrays.asList(null,java.time.OffsetDateTime.ofInstant(time.instant().plusSeconds(5),java.time.ZoneOffset.UTC),java.time.OffsetDateTime.MAX))
                assertTrue(assertThrows(SqlExecutionException.class,()->client.execute(w.key(),lease,id->fail())).outcomeUnknown());
            Thread.currentThread().interrupt();try{assertTrue(assertThrows(SqlExecutionException.class,()->client.execute(w.key(),time.lease(86400),id->fail())).outcomeUnknown());assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
            assertEquals(0,w.submits.get());assertEquals(0,w.statuses.get());
        }
    }
    @Test void lostSubmitStillOnlyQueriesStatusUnderSourceBudget() throws Exception {
        try(var w=new Wire(Duration.ofSeconds(2))){
            w.mode="drop";var time=new BudgetClock();var client=new SqlExecutionClient(w.transport,Duration.ofSeconds(2),Duration.ofMillis(1),time,time.ticks::get);
            assertEquals(12L,client.execute(w.key(),time.lease(86400),id->{}).affectedRows());assertEquals(1,w.submits.get());assertEquals(1,w.statuses.get());
        }
    }

}
