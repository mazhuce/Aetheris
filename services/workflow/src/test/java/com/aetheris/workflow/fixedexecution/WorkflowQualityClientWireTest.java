package com.aetheris.workflow.fixedexecution;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.execution.WorkflowQualityParent;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static com.aetheris.workflow.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual HTTP and Ed25519 proofs; quality results and permission state are fixture data. */
class WorkflowQualityClientWireTest {
    private static final WorkflowQualityParent.Reference PARENT=new WorkflowQualityParent.Reference(101,202,3,7,"a".repeat(64));
    private static final String FINISHED="2026-10-25T15:10:00Z";
    private static final String CHILD_HASH="b".repeat(64),DEFINITION_HASH="c".repeat(64);

    @Test void submitSendsOnlyItsSealedParentExactlyOnceAndReadsBoundResult() throws Exception {
        try(var peer=new Peer()){
            var result=peer.client.submit(PARENT);
            assertEquals(17,result.resultId());assertEquals(9,result.ruleId());assertEquals(CHILD_HASH,result.manifestSha256());
            assertEquals(23L,result.metadataReceiptId());assertEquals("passed",result.state());assertTrue(result.terminal());
            assertEquals(0L,result.violationCount());assertEquals(0,result.thresholdValue());assertEquals(12L,result.durationMs());assertEquals(FINISHED,result.finishedAt());
            assertEquals(parse(bytes(PARENT),65536),peer.lastRequest.get());assertEquals(1,peer.calls("submit"));assertEquals(0,peer.calls("status"));assertEquals(0,peer.calls("definition"));
        }
    }
    @Test void runningUnknownFailedAndErrorKeepTheirDistinctResultContracts() throws Exception {
        try(var peer=new Peer()){
            for(String state:List.of("running","unknown")){
                peer.change=body->{body.put("state",state).putNull("violationCount").putNull("durationMs").putNull("metadataReceiptId").putNull("finishedAt");return body;};
                var result=peer.client.status(PARENT);assertEquals(state,result.state());assertFalse(result.terminal());assertNull(result.violationCount());assertNull(result.metadataReceiptId());
            }
            peer.change=body->body.put("state","failed").put("violationCount",2).put("thresholdValue",1);
            var failed=peer.client.status(PARENT);assertTrue(failed.terminal());assertEquals("failed",failed.state());assertEquals(2L,failed.violationCount());
            peer.change=body->body.put("state","error").putNull("violationCount").putNull("durationMs");
            var error=peer.client.status(PARENT);assertTrue(error.terminal());assertEquals("error",error.state());assertNull(error.violationCount());assertNull(error.durationMs());assertEquals(23L,error.metadataReceiptId());assertEquals(FINISHED,error.finishedAt());
            assertEquals(0,peer.calls("submit"));
        }
    }
    @Test void status404IsNotAResultAndDoesNotSubmit() throws Exception {
        try(var peer=new Peer()){
            peer.httpStatus=404;peer.raw=ignored->"{\"reason\":\"CHILD_NOT_FOUND\"}";
            assertNull(peer.client.status(PARENT));assertEquals(1,peer.calls("status"));assertEquals(0,peer.calls("submit"));
            assertThrows(IllegalStateException.class,()->peer.client.submit(PARENT));assertEquals(1,peer.calls("submit"));
        }
    }
    @Test void everyParentFieldAndItsExactShapeMustReflectTheRequestedReference() throws Exception {
        try(var peer=new Peer()){
            List<Consumer<ObjectNode>> mutations=List.of(
                    body->((ObjectNode)body.get("parent")).put("instanceId",102),
                    body->((ObjectNode)body.get("parent")).put("attemptId",203),
                    body->((ObjectNode)body.get("parent")).put("fence",4),
                    body->((ObjectNode)body.get("parent")).put("executionRevision",8),
                    body->((ObjectNode)body.get("parent")).put("manifestSha256","d".repeat(64)),
                    body->((ObjectNode)body.get("parent")).put("instanceId",101.0),
                    body->((ObjectNode)body.get("parent")).put("attemptId","202"),
                    body->((ObjectNode)body.get("parent")).remove("fence"),
                    body->((ObjectNode)body.get("parent")).put("actor","admin"),
                    body->body.putNull("parent"));
            for(var mutation:mutations)rejectResult(peer,mutation);
            assertEquals(0,peer.calls("submit"));
        }
    }
    @Test void resultReferencesAndScalarTypesAreStrictWithoutCoercion() throws Exception {
        try(var peer=new Peer()){
            List<Consumer<ObjectNode>> mutations=List.of(
                    body->body.put("resultId",0),body->body.put("resultId",-1),body->body.put("resultId","17"),body->body.put("resultId",true),body->body.put("resultId",17.0),
                    body->body.set("resultId",JSON.getNodeFactory().numberNode(new java.math.BigInteger("9223372036854775808"))),
                    body->body.put("ruleId",0),body->body.put("ruleId",9.0),body->body.putNull("ruleId"),
                    body->body.put("manifestSha256","B".repeat(64)),body->body.put("manifestSha256","b".repeat(63)),body->body.put("manifestSha256",17),body->body.putNull("manifestSha256"),
                    body->body.put("thresholdValue",-1),body->body.put("thresholdValue","0"),body->body.put("thresholdValue",false),body->body.put("thresholdValue",0.0),
                    body->body.put("durationMs",-1),body->body.put("durationMs","12"),body->body.put("durationMs",true),body->body.put("durationMs",12.0),
                    body->body.put("state","succeeded"),body->body.put("state","PASSED"),body->body.putNull("state"),body->body.put("state",true),
                    body->body.remove("resultId"),body->body.put("sql","SELECT 1"));
            for(var mutation:mutations)rejectResult(peer,mutation);
        }
    }
    @Test void terminalReceiptsCannotBeMissingZeroNegativeOrCoerced() throws Exception {
        try(var peer=new Peer()){
            List<Consumer<ObjectNode>> mutations=List.of(body->body.putNull("metadataReceiptId"),body->body.put("metadataReceiptId",0),
                    body->body.put("metadataReceiptId",-1),body->body.put("metadataReceiptId","23"),body->body.put("metadataReceiptId",23.0),body->body.put("metadataReceiptId",true));
            for(String state:List.of("passed","failed","error"))for(var mutation:mutations){
                peer.change=body->{terminal(body,state);mutation.accept(body);return body;};assertThrows(RuntimeException.class,()->peer.client.status(PARENT),state);
            }
        }
    }
    @Test void passedAndFailedRequireCountsThatAgreeWithTheFrozenThreshold() throws Exception {
        try(var peer=new Peer()){
            List<Consumer<ObjectNode>> mutations=List.of(
                    body->body.put("violationCount",1),body->body.putNull("violationCount"),body->body.put("violationCount",-1),
                    body->body.put("violationCount","0"),body->body.put("violationCount",false),body->body.put("violationCount",0.0),
                    body->body.put("state","failed"),body->body.put("state","running"),body->body.put("state","unknown"),body->body.put("state","error"),
                    body->body.putNull("durationMs"));
            for(var mutation:mutations)rejectResult(peer,mutation);
            peer.change=body->body.put("state","passed").put("thresholdValue",2).put("violationCount",2);
            assertEquals("passed",peer.client.status(PARENT).state());
            peer.change=body->body.put("state","failed").put("thresholdValue",2).put("violationCount",3);
            assertEquals("failed",peer.client.status(PARENT).state());
        }
    }
    @Test void everyTerminalNeedsAnOffsetTimestampWithNoMissingOrCoercedValue() throws Exception {
        try(var peer=new Peer()){
            List<Consumer<ObjectNode>> mutations=List.of(body->body.putNull("finishedAt"),body->body.put("finishedAt",""),
                    body->body.put("finishedAt","2026-10-25"),body->body.put("finishedAt","2026-10-25T15:10:00"),
                    body->body.put("finishedAt","2026-02-30T15:10:00Z"),body->body.put("finishedAt",17),body->body.put("finishedAt",false));
            for(String state:List.of("passed","failed","error"))for(var mutation:mutations){
                peer.change=body->{terminal(body,state);mutation.accept(body);return body;};assertThrows(RuntimeException.class,()->peer.client.status(PARENT),state);
            }
        }
    }
    @Test void duplicateTrailingNonObjectAndOversizedResultJsonAreRejectedEvenWhenSigned() throws Exception {
        try(var peer=new Peer()){
            List<UnaryOperator<String>> mutations=List.of(
                    raw->raw.replace("\"resultId\":17","\"resultId\":17,\"resultId\":17"),
                    raw->raw.replace("\"fence\":3","\"fence\":3,\"fence\":3"),
                    raw->raw+" {}",raw->"null",raw->"[]",raw->"true",raw->raw+" ".repeat(32768));
            for(var mutation:mutations){peer.raw=mutation;assertThrows(RuntimeException.class,()->peer.client.status(PARENT));}
        }
    }
    @Test void unsignedTamperedWrongNonceAndStatusProofsCannotAuthenticateAnyResponse() throws Exception {
        try(var peer=new Peer()){
            for(String fault:List.of("unsigned","tampered","wrong-nonce","wrong-status"))for(String operation:List.of("submit","status","definition")){
                peer.fault=fault;int before=peer.calls(operation);
                var error=assertThrows(SignedHttpClient.TransportException.class,()->call(peer,operation));
                assertEquals(SignedHttpClient.Failure.UNTRUSTED_RESPONSE,error.failure());assertEquals(before+1,peer.calls(operation));
            }
            peer.httpStatus=404;peer.fault="unsigned";assertThrows(SignedHttpClient.TransportException.class,()->peer.client.status(PARENT));
        }
    }
    @Test void deliveryFailureNeverRetriesSubmitAndExplicitStatusUsesTheSameParent() throws Exception {
        try(var peer=new Peer()){
            peer.fault="disconnect";assertThrows(SignedHttpClient.TransportException.class,()->peer.client.submit(PARENT));
            assertEquals(1,peer.calls("submit"));assertEquals(0,peer.calls("status"));
            peer.fault="";assertEquals(17,peer.client.status(PARENT).resultId());
            assertEquals(1,peer.calls("submit"));assertEquals(1,peer.calls("status"));assertEquals(parse(bytes(PARENT),65536),peer.lastRequest.get());
        }
    }
    @Test void nonSuccessHttpStatusesFailWithoutFallbackOrAnotherSubmission() throws Exception {
        try(var peer=new Peer()){
            for(String operation:List.of("submit","status","definition"))for(int status:List.of(400,401,403,409,500,503)){
                peer.httpStatus=status;int before=peer.calls(operation);assertThrows(IllegalStateException.class,()->call(peer,operation));assertEquals(before+1,peer.calls(operation));
            }
            peer.httpStatus=200;assertThrows(IllegalStateException.class,()->peer.client.submit(PARENT));
            peer.httpStatus=202;assertThrows(IllegalStateException.class,()->peer.client.status(PARENT));assertThrows(IllegalStateException.class,()->peer.client.approvedDefinition(9));
        }
    }
    @Test void approvedDefinitionRequiresTheExactRuleApprovedEnabledRevisionAndHash() throws Exception {
        try(var peer=new Peer()){
            var definition=peer.client.approvedDefinition(9);assertEquals(9,definition.ruleId());assertEquals(2,definition.executionRevision());assertEquals(DEFINITION_HASH,definition.definitionSha256());
            assertEquals(JSON.createObjectNode().put("ruleId",9),peer.lastRequest.get());assertEquals(0,peer.calls("submit"));
            List<Consumer<ObjectNode>> mutations=List.of(
                    body->body.put("ruleId",10),body->body.put("ruleId",9.0),body->body.put("ruleId","9"),
                    body->body.put("executionRevision",0),body->body.put("executionRevision",2.0),body->body.put("executionRevision","2"),
                    body->body.put("definitionSha256","C".repeat(64)),body->body.putNull("definitionSha256"),
                    body->body.put("approved",false),body->body.put("approved","true"),body->body.put("approved",1),body->body.putNull("approved"),
                    body->body.put("status","disabled"),body->body.put("status","quarantined"),body->body.put("status","unknown"),body->body.put("status","active"),body->body.putNull("status"),
                    body->body.put("sql","SELECT 1"),body->body.remove("executionRevision"));
            for(var mutation:mutations){peer.change=body->{mutation.accept(body);return body;};assertThrows(RuntimeException.class,()->peer.client.approvedDefinition(9));}
            assertEquals(0,peer.calls("submit"));assertEquals(0,peer.calls("status"));
        }
    }
    @Test void definitionJsonAndHttp404CannotMasqueradeAsApprovedMetadata() throws Exception {
        try(var peer=new Peer()){
            for(UnaryOperator<String> mutation:List.<UnaryOperator<String>>of(
                    raw->raw.replace("\"approved\":true","\"approved\":true,\"approved\":true"),raw->raw+" {}",raw->"[]",raw->raw+" ".repeat(65536))){
                peer.raw=mutation;assertThrows(RuntimeException.class,()->peer.client.approvedDefinition(9));
            }
            peer.raw=UnaryOperator.identity();peer.httpStatus=404;assertThrows(IllegalStateException.class,()->peer.client.approvedDefinition(9));assertEquals(0,peer.calls("submit"));
        }
    }
    private static void terminal(ObjectNode body,String state){
        body.put("state",state);if(state.equals("failed"))body.put("violationCount",1);
        if(state.equals("error"))body.putNull("violationCount").putNull("durationMs");
    }
    private static void rejectResult(Peer peer,Consumer<ObjectNode> mutation){
        peer.change=body->{mutation.accept(body);return body;};assertThrows(RuntimeException.class,()->peer.client.status(PARENT));
    }
    private static Object call(Peer peer,String operation){return switch(operation){case "submit"->peer.client.submit(PARENT);case "status"->peer.client.status(PARENT);case "definition"->peer.client.approvedDefinition(9);default->throw new AssertionError();};}

    private static final class Peer implements AutoCloseable {
        final Clock clock=Clock.systemUTC();
        final ProtocolSigner workflowSigner,qualitySigner;
        final ProtocolVerifier verifier;
        final HttpServer server;
        final ExecutorService handlers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
        final SignedHttpClient transport;
        final WorkflowQualityClient client;
        final ConcurrentMap<String,AtomicInteger> counts=new ConcurrentHashMap<>();
        final Set<String> nonces=ConcurrentHashMap.newKeySet();
        final AtomicReference<JsonNode> lastRequest=new AtomicReference<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        volatile UnaryOperator<ObjectNode> change=UnaryOperator.identity();
        volatile UnaryOperator<String> raw=UnaryOperator.identity();
        volatile String fault="";
        volatile int httpStatus;
        Peer() throws Exception {
            var generator=KeyPairGenerator.getInstance("Ed25519");var workflow=generator.generateKeyPair();var quality=generator.generateKeyPair();
            var registry=new KeyRegistry(List.of(new KeyRegistry.Entry("workflow-key",KeyPurpose.WORKLOAD,"workflow",workflow.getPublic()),
                    new KeyRegistry.Entry("quality-key",KeyPurpose.WORKLOAD,"quality",quality.getPublic())));
            verifier=new ProtocolVerifier(registry,clock,TimePolicy.defaults());
            workflowSigner=new ProtocolSigner("workflow-key",KeyPurpose.WORKLOAD,"workflow",workflow.getPrivate(),registry,clock,TimePolicy.defaults());
            qualitySigner=new ProtocolSigner("quality-key",KeyPurpose.WORKLOAD,"quality",quality.getPrivate(),registry,clock,TimePolicy.defaults());
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);server.setExecutor(handlers);
            server.createContext("/",exchange->{try{
                String path=exchange.getRequestURI().getRawPath();
                String operation=switch(path){case WorkflowQualityClient.BASE+"submit"->"submit";case WorkflowQualityClient.BASE+"status"->"status";case WorkflowQualityClient.BASE+"definition"->"definition";default->throw new AssertionError("Unexpected child path");};
                counts.computeIfAbsent(operation,ignored->new AtomicInteger()).incrementAndGet();
                assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getRawQuery());
                for(String header:List.of(SignedHttpClient.USER_PROOF,"Authorization","Cookie","X-Internal-Token","X-Aetheris-Identity"))assertNull(exchange.getRequestHeaders().getFirst(header));
                byte[] requestBytes=exchange.getRequestBody().readNBytes(65537);assertTrue(requestBytes.length<=65536);
                var proof=verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest("quality","POST",path,
                        BodyHash.sha256(requestBytes),"quality.workflow-child."+operation,BodyHash.userProof(null)));
                assertEquals("workflow",proof.caller());assertTrue(nonces.add(proof.proof().nonce()),"Transport repeated the same signed request");
                var request=parse(requestBytes,65536);lastRequest.set(request.deepCopy());
                if(operation.equals("definition")){fields(request,"ruleId");assertEquals(9,number(request,"ruleId",1));}
                else assertEquals(PARENT,WorkflowQualityParent.Reference.parse(requestBytes));
                if(fault.equals("disconnect"))return;
                ObjectNode body=operation.equals("definition")?definition():result();body=change.apply(body);
                byte[] response=raw.apply(new String(bytes(body),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                int status=httpStatus==0?(operation.equals("submit")?202:200):httpStatus;long now=clock.instant().getEpochSecond();
                if(!fault.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,qualitySigner.sign(new WorkloadResponseProof(2,"quality","workflow",
                        fault.equals("wrong-nonce")?Nonce.random():proof.proof().nonce(),fault.equals("wrong-status")?status+1:status,BodyHash.sha256(response),now,now+30)));
                if(fault.equals("tampered"))response="{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
            }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}});server.start();
            transport=new SignedHttpClient("workflow",workflowSigner,verifier,clock,TimePolicy.defaults(),
                    Map.of("quality",URI.create("http://127.0.0.1:"+server.getAddress().getPort())),
                    Map.of("quality",Set.of("quality.workflow-child.submit","quality.workflow-child.status","quality.workflow-child.definition")),
                    new SignedHttpClient.Limits(65536,131072,8,Duration.ofMillis(500),Duration.ofSeconds(2)));
            client=new WorkflowQualityClient(transport);
        }
        int calls(String operation){var count=counts.get(operation);return count==null?0:count.get();}
        private static ObjectNode definition(){return JSON.createObjectNode().put("ruleId",9).put("executionRevision",2).put("definitionSha256",DEFINITION_HASH).put("approved",true).put("status","enabled");}
        private static ObjectNode result(){var body=JSON.createObjectNode().put("resultId",17).put("ruleId",9).put("manifestSha256",CHILD_HASH).put("metadataReceiptId",23)
                .put("state","passed").put("violationCount",0).put("thresholdValue",0).put("durationMs",12).put("finishedAt",FINISHED);body.set("parent",JSON.valueToTree(PARENT));return body;}
        @Override public void close() throws Exception {
            int port=server.getAddress().getPort();transport.close();server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));
            try(var socket=new Socket()){assertThrows(java.io.IOException.class,()->socket.connect(new InetSocketAddress("127.0.0.1",port),500));}
            if(failure.get()!=null)throw new AssertionError("Signed quality wire fixture failed",failure.get());
        }
    }
}
