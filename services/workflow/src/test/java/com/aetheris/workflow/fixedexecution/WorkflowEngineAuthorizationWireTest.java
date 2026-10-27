package com.aetheris.workflow.fixedexecution;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.aetheris.workflow.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP and Ed25519 workload proofs; Auth decisions are controlled fixture responses. */
class WorkflowEngineAuthorizationWireTest {
    ObjectNode manifest(){var n=JSON.createObjectNode().put("schemaVersion",1).put("origin","workflow").put("operation","workflow.fixed-spark.dispatch")
        .put("resourceId",11).put("runId",12).put("attemptId",13).put("fence",2).put("executionRevision",7).put("definitionSha256","a".repeat(64));
        n.set("actor",JSON.createObjectNode().put("kind","manual").put("uid",42).put("username","original-user").put("accessVersion",3).put("requestId","original-request"));
        n.set("approval",JSON.createObjectNode().put("uid",43).put("username","original-approver").put("accessVersion",4).put("revision",7).put("definitionSha256","a".repeat(64)));
        n.set("execution",JSON.createObjectNode().put("taskType","spark"));n.set("parameters",JSON.createObjectNode().put("bizDate","2026-10-26"));n.putNull("parent");return n;}
    boolean call(Peer p){return p.client.authorize(manifest(),"b".repeat(64),"c".repeat(64));}
    @Test void sendsOnlyTheOriginalActorApprovalAndExactSourceBindingForAllThreeKinds()throws Exception {try(var p=new Peer()){
        for(String kind:List.of("manual","cron","pipeline")){var n=manifest();((ObjectNode)n.get("actor")).put("kind",kind);assertTrue(p.client.authorize(n,"b".repeat(64),"c".repeat(64)));assertEquals(n.get("actor"),p.request.get().get("actor"));assertEquals(n.get("approval"),p.request.get().get("approval"));assertEquals(13,p.request.get().at("/binding/attemptId").asLong());assertEquals("c".repeat(64),p.request.get().at("/binding/engineDefinitionSha256").asText());}assertEquals(3,p.calls.get());}}
    @Test void inactiveAndDeniedResponsesRemainExplicitDenials()throws Exception {try(var p=new Peer()){
        p.change=n->{n.put("allowed",false).put("reason","SUBJECT_INACTIVE");n.putArray("checks");return n;};assertFalse(call(p));
        p.change=n->{n.put("allowed",false).put("reason","PERMISSION_DENIED");((ObjectNode)n.get("checks").get(0)).put("allowed",false);return n;};assertFalse(call(p));}}
    @Test void everyEchoedBindingFieldAndItsTypeMustMatch()throws Exception {try(var p=new Peer()){
        List<Consumer<ObjectNode>> changes=List.of(n->n.put("attemptId",14),n->n.put("fence",3),n->n.put("taskId",12),n->n.put("instanceId",13),n->n.put("executionRevision",8),n->n.put("engine","flink"),n->n.put("manifestSha256","d".repeat(64)),n->n.put("definitionSha256","d".repeat(64)),n->n.put("engineDefinitionSha256","d".repeat(64)),n->n.put("attemptId",13.0),n->n.remove("fence"),n->n.put("username","admin"));
        for(var change:changes){p.change=n->{change.accept((ObjectNode)n.get("binding"));return n;};assertThrows(IllegalStateException.class,()->call(p));}}}
    @Test void permissiveMissingOrReorderedChecksCannotBecomePermission()throws Exception {try(var p=new Peer()){
        List<Consumer<ObjectNode>> changes=List.of(n->n.remove("reason"),n->n.put("reason","PERMISSION_DENIED"),n->n.put("allowed","true"),n->n.putArray("checks"),n->((ObjectNode)n.get("checks").get(0)).put("allowed",false),n->((ObjectNode)n.get("checks").get(0)).put("subject","approval"),n->((ObjectNode)n.get("checks").get(2)).put("resourceId",11));
        for(var change:changes){p.change=n->{change.accept(n);return n;};assertThrows(IllegalStateException.class,()->call(p));}}}
    @Test void duplicateTrailingOversizedUnsignedAndWrongNonceResponsesFailClosedWithoutRetry()throws Exception {try(var p=new Peer()){
        for(String fault:List.of("duplicate","trailing","oversized","unsigned","wrong-nonce","drop")){p.fault=fault;int before=p.calls.get();assertThrows(RuntimeException.class,()->call(p));assertEquals(before+1,p.calls.get());}}}
    @Test void malformedOriginalIdentitiesAreRejectedBeforeAnyRequest()throws Exception {try(var p=new Peer()){
        for(String field:List.of("uid","accessVersion","username","requestId")){var n=manifest();((ObjectNode)n.get("actor")).remove(field);assertThrows(RuntimeException.class,()->p.client.authorize(n,"b".repeat(64),"c".repeat(64)));}assertEquals(0,p.calls.get());}}
    static final class Peer implements AutoCloseable {
        final HttpServer server;final SignedHttpClient transport;final WorkflowEngineAuthorizationClient client;
        final AtomicInteger calls=new AtomicInteger();final AtomicReference<JsonNode> request=new AtomicReference<>();final AtomicReference<Throwable> failure=new AtomicReference<>();
        volatile UnaryOperator<ObjectNode> change=UnaryOperator.identity();volatile String fault="";
        Peer()throws Exception {var clock=Clock.systemUTC();var generator=KeyPairGenerator.getInstance("Ed25519");var workflow=generator.generateKeyPair();var auth=generator.generateKeyPair();
            var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("wk",KeyPurpose.WORKLOAD,"workflow",workflow.getPublic()),new KeyRegistry.Entry("ak",KeyPurpose.WORKLOAD,"auth",auth.getPublic())));
            var verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());var signer=new ProtocolSigner("wk",KeyPurpose.WORKLOAD,"workflow",workflow.getPrivate(),registry,clock,TimePolicy.defaults());var responder=new ProtocolSigner("ak",KeyPurpose.WORKLOAD,"auth",auth.getPrivate(),registry,clock,TimePolicy.defaults());
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);server.createContext("/",x->{try{
                calls.incrementAndGet();byte[] raw=x.getRequestBody().readAllBytes();assertEquals(WorkflowEngineAuthorizationClient.PATH,x.getRequestURI().getRawPath());
                assertNull(x.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF));assertNull(x.getRequestHeaders().getFirst("Authorization"));
                var proof=verifier.verifyRequest(x.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("auth","POST",WorkflowEngineAuthorizationClient.PATH,BodyHash.sha256(raw),WorkflowEngineAuthorizationClient.OPERATION,BodyHash.userProof(null)));assertEquals("workflow",proof.caller());
                JsonNode input=parse(raw,16384);request.set(input);if("drop".equals(fault))return;
                var body=JSON.createObjectNode().put("allowed",true).putNull("reason");body.set("binding",input.get("binding"));var checks=body.putArray("checks");
                checks.addObject().put("subject","actor").put("resourceType","workflow_task").put("resourceId",11).put("action","execute").put("allowed",true);
                checks.addObject().put("subject","approval").put("resourceType","workflow_task").put("resourceId",11).put("action","update").put("allowed",true);
                checks.addObject().put("subject","approval").put("resourceType","platform").putNull("resourceId").put("action","manage").put("allowed",true);
                String response=change.apply(body).toString();if("duplicate".equals(fault))response=response.replace("\"allowed\":true","\"allowed\":false,\"allowed\":true");if("trailing".equals(fault))response+=" {}";if("oversized".equals(fault))response+=" ".repeat(20000);
                byte[] data=response.getBytes(StandardCharsets.UTF_8);long now=clock.instant().getEpochSecond();if(!"unsigned".equals(fault))x.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,responder.sign(new WorkloadResponseProof(2,"auth","workflow","wrong-nonce".equals(fault)?Nonce.random():proof.proof().nonce(),200,BodyHash.sha256(data),now,now+30)));
                x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);
            }catch(Throwable bad){failure.compareAndSet(null,bad);}finally{x.close();}});server.start();
            transport=new SignedHttpClient("workflow",signer,verifier,clock,TimePolicy.defaults(),Map.of("auth",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),Map.of("auth",Set.of(WorkflowEngineAuthorizationClient.OPERATION)),new SignedHttpClient.Limits(16384,32768,4,Duration.ofMillis(500),Duration.ofSeconds(2)));client=new WorkflowEngineAuthorizationClient(transport);
        }
        public void close(){transport.close();server.stop(0);if(failure.get()!=null)throw new AssertionError("Auth wire fixture failed",failure.get());}
    }
}
