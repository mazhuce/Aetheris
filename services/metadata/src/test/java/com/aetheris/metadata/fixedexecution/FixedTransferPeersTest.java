package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FixedTransferPeersTest {
    static final String DEFINITION="{\"batchSize\": 500, \"sourceSql\": \"SELECT 1\", \"writeMode\": \"append\", \"targetTable\": \"business.output\", \"schemaVersion\": 1, \"sourceDatasourceId\": 5, \"targetDatasourceId\": 6}";
    static final ObjectMapper JSON=new ObjectMapper();
    final MetadataProofFixture keys=new MetadataProofFixture();
    HttpServer server;ExecutorService handlers;SignedHttpClient source,auth;FixedTransferPeers peers;
    final AtomicReference<String> sourceMode=new AtomicReference<>("normal"),authMode=new AtomicReference<>("normal");
    final AtomicInteger sourceCalls=new AtomicInteger(),authCalls=new AtomicInteger();
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();
    String canonical,manifestHash;FixedTransferManifest.Submit submit;
    @BeforeEach void setup() throws Exception {
        var node=JSON.createObjectNode();node.put("schemaVersion",1);node.put("origin","ingestion");node.put("operation",FixedTransferManifest.OPERATION);
        node.put("resourceId",9);node.put("runId",11);node.put("attemptId",0);node.put("fence",1);node.put("executionRevision",2);
        String hash=BodyHash.sha256(DEFINITION.getBytes(StandardCharsets.UTF_8));node.put("definitionSha256",hash);
        node.set("actor",JSON.valueToTree(Map.of("kind","manual","uid",2,"username","operator","accessVersion",3,"requestId","fixed-wire")));
        node.set("approval",JSON.valueToTree(Map.of("uid",3,"username","approver","accessVersion",4,"revision",2,"definitionSha256",hash)));
        node.set("execution",JSON.readTree(DEFINITION));node.set("parameters",JSON.createObjectNode());node.putNull("parent");
        canonical=JSON.writeValueAsString(node);manifestHash=BodyHash.sha256(canonical.getBytes(StandardCharsets.UTF_8));
        submit=new FixedTransferManifest.Submit(11,0,1,2,manifestHash);
        handlers=Executors.newFixedThreadPool(2);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(handlers);
        server.createContext("/",exchange->{
            try {
                String path=exchange.getRequestURI().getRawPath();boolean lookup=path.equals("/internal/v2/executions/lookup");
                assertTrue(lookup||path.equals("/internal/v2/permissions/fixed-execution-authorize"));
                String receiver=lookup?"ingestion":"auth",operation=lookup?"ingestion.execution.lookup":"auth.fixed-execution.authorize";
                String mode=lookup?sourceMode.get():authMode.get();if(lookup)sourceCalls.incrementAndGet();else authCalls.incrementAndGet();
                assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getRawQuery());
                for(String header:List.of("X-Internal-Token","X-Aetheris-User-Proof","X-User-Name","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(header));
                byte[] raw=exchange.getRequestBody().readAllBytes();var body=JSON.readTree(raw);
                var proof=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest(receiver,"POST",path,BodyHash.sha256(raw),operation,BodyHash.userProof(null))).proof();
                assertEquals("metadata",proof.caller());ObjectNode answer;int status=200;
                if(lookup) {
                    assertEquals(JSON.readTree("{\"runId\":11,\"fence\":1}"),body);
                    answer=JSON.createObjectNode();answer.put("canonicalManifest",canonical);answer.put("manifestSha256",manifestHash);
                    answer.put("status","running");answer.put("leaseExpiresAt",keys.clock.instant().getEpochSecond()+120);
                    if(mode.equals("expired"))answer.put("leaseExpiresAt",keys.clock.instant().getEpochSecond()-1);
                    if(mode.equals("unknown"))answer.put("status","unknown");
                    if(mode.equals("wrong-hash"))answer.put("manifestSha256","a".repeat(64));
                    if(mode.equals("not-found"))status=404;
                }else {
                    assertEquals("operator",body.get("actor").get("username").asText());assertEquals("approver",body.get("approval").get("username").asText());
                    assertEquals(6,body.get("targets").get("targetDatasourceId").asLong());assertTrue(body.get("parent").isNull());
                    answer=JSON.createObjectNode();answer.set("binding",body.get("binding"));answer.put("allowed",true);answer.putNull("reason");
                    var checks=answer.putArray("checks");
                    checks.add(JSON.valueToTree(Map.of("subject","actor","resourceType","ingestion_task","resourceId",9,"action","execute","allowed",true)));
                    checks.add(JSON.valueToTree(Map.of("subject","approval","resourceType","datasource","resourceId",5,"action","query","allowed",true)));
                    checks.add(JSON.valueToTree(Map.of("subject","approval","resourceType","datasource","resourceId",6,"action","write","allowed",true)));
                    if(mode.equals("missing-check"))checks.remove(2);
                    if(mode.equals("wrong-binding"))((ObjectNode)answer.get("binding")).put("runId",12);
                    if(mode.equals("wrong-target"))((ObjectNode)checks.get(2)).put("resourceId",7);
                    if(mode.equals("denied")){answer.put("allowed",false);answer.put("reason","PERMISSION_DENIED");((ObjectNode)checks.get(2)).put("allowed",false);}
                    if(mode.equals("inactive")){answer.put("allowed",false);answer.put("reason","SUBJECT_INACTIVE");checks.removeAll();}
                    if(mode.equals("inconsistent-allow"))((ObjectNode)checks.get(2)).put("allowed",false);
                    if(mode.equals("unavailable"))status=503;
                }
                byte[] bytes=JSON.writeValueAsBytes(answer);long now=keys.clock.instant().getEpochSecond();
                if(!mode.equals("unsigned"))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,
                        keys.workload(receiver).sign(new WorkloadResponseProof(2,receiver,"metadata",mode.equals("wrong-nonce")?Nonce.random():proof.nonce(),status,BodyHash.sha256(bytes),now,now+30)));
                if(mode.equals("tampered"))bytes="{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
            }catch(Throwable failure){serverFailure.compareAndSet(null,failure);}
            finally{exchange.close();}
        });server.start();URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var limits=new SignedHttpClient.Limits(65536,1024*1024,4,Duration.ofMillis(500),Duration.ofSeconds(2));
        source=new SignedHttpClient("metadata",keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("ingestion",origin),Map.of("ingestion",Set.of("ingestion.execution.lookup")),limits);
        auth=new SignedHttpClient("metadata",keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("auth",origin),Map.of("auth",Set.of("auth.fixed-execution.authorize")),limits);
        peers=new FixedTransferPeers(source,auth,keys.clock);
    }
    @AfterEach void stop() throws Exception {
        source.close();auth.close();server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));
        if(serverFailure.get()!=null)throw new AssertionError("Fixed wire fixture failed",serverFailure.get());
    }
    @Test void sourceReadbackAndExactThreeChecksBindTheOriginalStoredIdentities() {
        var result=peers.resolveAndAuthorize(submit);assertEquals(11,result.binding().runId());assertEquals(5,result.definition().sourceDatasourceId());
        assertEquals(1,sourceCalls.get());assertEquals(1,authCalls.get());
    }
    @Test void expiredUnknownAndMissingSourceNeverReachAuth() {
        for(String mode:List.of("expired","unknown","not-found")) {
            sourceMode.set(mode);assertThrows(FixedTransferPeers.Rejected.class,()->peers.resolveAndAuthorize(submit));assertEquals(0,authCalls.get());
        }
    }
    @Test void unsignedTamperedNonceAndHashCannotBecomeSourceEvidence() {
        for(String mode:List.of("unsigned","tampered","wrong-nonce","wrong-hash")) {
            sourceMode.set(mode);assertThrows(FixedTransferPeers.Unavailable.class,()->peers.resolveAndAuthorize(submit));assertEquals(0,authCalls.get());
        }
    }
    @Test void authMustHaveValidSignatureBindingAndEveryFixedPermission() {
        for(String mode:List.of("unsigned","tampered","wrong-nonce","wrong-binding","wrong-target","missing-check","inconsistent-allow","unavailable")) {
            authMode.set(mode);assertThrows(FixedTransferPeers.Unavailable.class,()->peers.resolveAndAuthorize(submit));
        }
        for(String mode:List.of("denied","inactive")){authMode.set(mode);assertThrows(FixedTransferPeers.Rejected.class,()->peers.resolveAndAuthorize(submit));}
    }
    @Test void submitRejectsSqlActorWrongTypesAndOverflowBeforeNetwork() {
        String normal=FixedJson.string(submit);
        for(String body:List.of(normal.replace("\"runId\":11","\"runId\":\"11\""),normal.replace("\"runId\":11","\"runId\":11.0"),
                normal.replace("\"runId\":11","\"runId\":9223372036854775808"),normal.replace("\"fence\":1","\"fence\":2"),
                normal.substring(0,normal.length()-1)+",\"sql\":\"DELETE FROM business.t\"}",normal.substring(0,normal.length()-1)+",\"actor\":\"admin\"}",normal+" {}"))
            assertThrows(IllegalArgumentException.class,()->FixedTransferManifest.Submit.parse(body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(0,sourceCalls.get());assertEquals(0,authCalls.get());
    }
    @Test void manifestCannotReplaceApprovedDefinitionOrClaimAnotherRun() throws Exception {
        for(String field:List.of("actor","approval","parameters","parent","execution")) {
            ObjectNode changed=(ObjectNode)JSON.readTree(canonical);changed.set(field,JSON.readTree("{\"username\":\"admin\",\"sql\":\"DELETE FROM business.t\"}"));
            String replacement=JSON.writeValueAsString(changed),hash=BodyHash.sha256(replacement.getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class,()->FixedTransferManifest.parse(replacement,hash,new FixedTransferManifest.Submit(11,0,1,2,hash)));
        }
        assertThrows(IllegalArgumentException.class,()->FixedTransferManifest.parse(canonical,manifestHash,new FixedTransferManifest.Submit(12,0,1,2,manifestHash)));
        assertEquals(0,sourceCalls.get());assertEquals(0,authCalls.get());
    }
}
