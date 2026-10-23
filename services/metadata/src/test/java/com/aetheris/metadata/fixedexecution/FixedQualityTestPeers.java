package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Ed25519 loopback replacements; session/permission state is explicitly fixture controlled. */
final class FixedQualityTestPeers implements AutoCloseable {
    final MetadataProofFixture keys=new MetadataProofFixture();
    final AtomicBoolean allowed=new AtomicBoolean(true),sourceActive=new AtomicBoolean(true);
    volatile String fault="";volatile boolean faultOnSource;
    final AtomicInteger lookups=new AtomicInteger(),checks=new AtomicInteger();
    final ConcurrentMap<Long,FixedQualityManifest> manifests=new ConcurrentHashMap<>();
    final AtomicReference<Throwable> failure=new AtomicReference<>();
    final HttpServer server;final ExecutorService handlers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    final SignedHttpClient source,auth;final FixedQualityPeers peers;
    FixedQualityTestPeers() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);server.setExecutor(handlers);
        server.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getRawPath();boolean lookup=path.equals("/internal/v2/executions/lookup");
            assertTrue(lookup||path.equals("/internal/v2/permissions/fixed-execution-authorize"));
            String receiver=lookup?"quality":"auth",op=lookup?"quality.execution.lookup":"auth.fixed-execution.authorize";
            byte[] raw=exchange.getRequestBody().readNBytes(65537);assertTrue(raw.length<=65536);assertNull(exchange.getRequestURI().getRawQuery());
            for(String h:List.of(SignedHttpClient.USER_PROOF,"X-Internal-Token","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(h));
            var verified=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),new ExpectedRequest(receiver,"POST",path,BodyHash.sha256(raw),op,BodyHash.userProof(null)));
            assertEquals("metadata",verified.caller());var body=parse(raw);ObjectNode result=JSON.createObjectNode();int status=200;
            if(lookup){
                lookups.incrementAndGet();fields(body,"resultId","fence");var m=manifests.get(number(body,"resultId"));assertNotNull(m);assertEquals(1,number(body,"fence"));
                result.put("canonicalManifest",m.canonical()).put("manifestSha256",m.sha256()).put("status",sourceActive.get()?"running":"unknown").put("leaseExpiresAt",keys.clock.instant().getEpochSecond()+120);
            }else{
                checks.incrementAndGet();fields(body,"schemaVersion","binding","actor","approval","targets","parent");
                var m=manifests.get(body.path("binding").path("runId").asLong());assertNotNull(m);assertEquals(parse(bytes(m.authorizationRequest())),body);
                result.set("binding",body.get("binding"));result.put("allowed",allowed.get());if(allowed.get())result.putNull("reason");else result.put("reason","PERMISSION_DENIED");
                var list=result.putArray("checks");list.add(JSON.valueToTree(Map.of("subject","actor","resourceType","quality_rule","resourceId",m.binding().resourceId(),"action","execute","allowed",true)));
                list.add(JSON.valueToTree(Map.of("subject","approval","resourceType","datasource","resourceId",m.definition().datasourceId(),"action","query","allowed",allowed.get())));

            }
            if(!lookup&&fault.equals("wrong-binding"))((ObjectNode)result.get("binding")).put("runId",999);
            if(!lookup&&fault.equals("missing-check"))((com.fasterxml.jackson.databind.node.ArrayNode)result.get("checks")).remove(1);
            if(!lookup&&fault.equals("inactive")){result.put("allowed",false).put("reason","SUBJECT_INACTIVE");((com.fasterxml.jackson.databind.node.ArrayNode)result.get("checks")).removeAll();}
            byte[] response=bytes(result);long now=keys.clock.instant().getEpochSecond();
            if(!(fault.equals("unsigned")&&lookup==faultOnSource))exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,keys.workload(receiver).sign(new WorkloadResponseProof(2,receiver,"metadata",verified.proof().nonce(),status,BodyHash.sha256(response),now,now+30)));
            if(fault.equals("tampered")&&lookup==faultOnSource)response="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,response.length);exchange.getResponseBody().write(response);
        }catch(Throwable e){failure.compareAndSet(null,e);}finally{exchange.close();}});server.start();
        var origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());var limits=new SignedHttpClient.Limits(1024*1024,1024*1024,8,Duration.ofMillis(500),Duration.ofSeconds(2));
        source=new SignedHttpClient("metadata",keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("quality",origin),Map.of("quality",Set.of("quality.execution.lookup")),limits);
        auth=new SignedHttpClient("metadata",keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of("auth",origin),Map.of("auth",Set.of("auth.fixed-execution.authorize")),limits);
        peers=new FixedQualityPeers(source,auth,keys.clock);
    }
    FixedQualityManifest install(long run,long datasourceId,String type,String expression,Integer threshold,UnaryOperator<String> canonicalize){
        var definition=JSON.createObjectNode().put("schemaVersion",1).put("datasourceId",datasourceId).put("targetTable","business.input").put("ruleType",type).put("expression",expression);
        if(threshold==null)definition.putNull("threshold");else definition.put("threshold",threshold);
        String definitionHash=FixedTransferManifest.definitionHash(definition);
        var n=JSON.createObjectNode().put("schemaVersion",1).put("origin","quality").put("operation",FixedExecutionOperation.QUALITY.operation()).put("resourceId",9).put("runId",run).put("attemptId",0).put("fence",1).put("executionRevision",2).put("definitionSha256",definitionHash);
        n.set("actor",JSON.valueToTree(Map.of("kind","manual","uid",2,"username","operator","accessVersion",3,"requestId","fixed-quality-test")));
        n.set("approval",JSON.valueToTree(Map.of("uid",3,"username","approver","accessVersion",4,"revision",2,"definitionSha256",definitionHash)));
        n.set("execution",definition);n.set("parameters",JSON.createObjectNode());n.putNull("parent");
        String canonical=canonicalize.apply(string(n)),hash=BodyHash.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var m=FixedQualityManifest.parse(canonical,hash,new FixedExecutionAdapter.Submission(run,0,1,2,hash));manifests.put(run,m);return m;
    }
    @Override public void close() throws Exception{
        int port=server.getAddress().getPort();source.close();auth.close();server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));
        try(var probe=new java.net.Socket()){assertThrows(java.io.IOException.class,()->probe.connect(new InetSocketAddress("127.0.0.1",port),500));}
        if(failure.get()!=null)throw new AssertionError("Signed peer replacement failed",failure.get());
    }
}
