package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.execution.WorkflowQualityParent;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.UnaryOperator;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Ed25519 loopback peers; source state and permission decisions remain controlled fixtures. */
final class FixedQualityChildTestPeers implements AutoCloseable {
    final MetadataProofFixture keys=new MetadataProofFixture();
    final AtomicInteger qualityLookups=new AtomicInteger(),parentLookups=new AtomicInteger(),authChecks=new AtomicInteger();
    final AtomicReference<Throwable> failure=new AtomicReference<>();
    final AtomicReference<com.fasterxml.jackson.databind.JsonNode> lastParentRequest=new AtomicReference<>(),lastAuthRequest=new AtomicReference<>();
    final HttpServer server;
    final ExecutorService handlers=new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    final SignedHttpClient source,auth,workflow;
    final FixedQualityPeers peers;
    final ObjectNode child,parent;
    volatile String fault="",faultTarget="workflow",parentState="running";
    volatile int parentHttpStatus=200,deniedCheck=-1;
    volatile Long parentLease;
    volatile UnaryOperator<ObjectNode> authResponse=n->n;
    FixedQualityChildTestPeers() throws Exception {this("manual");}
    FixedQualityChildTestPeers(String actorKind) throws Exception {
        var definition=JSON.createObjectNode().put("schemaVersion",1).put("datasourceId",5).put("targetTable","business.input")
                .put("ruleType","sql").put("expression","SELECT COUNT(*) FROM business.input").put("threshold",0);
        String ruleHash=FixedTransferManifest.definitionHash(definition);
        var task=JSON.createObjectNode().put("schemaVersion",1).put("taskType","quality").put("timeoutSeconds",1800).put("maxRetries",0)
                .put("qualityRuleId",9).put("qualityExecutionRevision",2).put("qualityDefinitionSha256",ruleHash);
        for(String field:List.of("datasourceId","sql","targetTable","jobDefinitionId","flinkEndpoint","flinkMode","eventTimeColumn",
                "watermarkExpression","allowedLatenessSeconds","lateDataPolicy","deadLetterTable"))task.putNull(field);
        task.set("dependencies",JSON.createObjectNode());String taskHash=WorkflowQualityParent.definitionHash(task);
        parent=JSON.createObjectNode().put("schemaVersion",1).put("origin","workflow").put("operation",WorkflowQualityParent.OPERATION)
                .put("resourceId",31).put("runId",901).put("attemptId",902).put("fence",3).put("executionRevision",7).put("definitionSha256",taskHash);
        parent.set("actor",JSON.valueToTree(Map.of("kind",actorKind,"uid",2,"username","operator","accessVersion",3,"requestId","quality-child-test")));
        parent.set("approval",JSON.valueToTree(Map.of("uid",30,"username","workflow_approver","accessVersion",8,"revision",7,"definitionSha256",taskHash)));
        parent.set("execution",task);parent.set("parameters",JSON.createObjectNode().put("bizDate","2026-10-25"));parent.putNull("parent");
        String parentHash=BodyHash.sha256(bytes(parent));var reference=new WorkflowQualityParent.Reference(901,902,3,7,parentHash);
        var parsed=WorkflowQualityParent.parse(string(parent),parentHash,reference);
        child=JSON.createObjectNode().put("schemaVersion",1).put("origin","quality").put("operation",FixedExecutionOperation.QUALITY.operation())
                .put("resourceId",9).put("runId",11).put("attemptId",0).put("fence",1).put("executionRevision",2).put("definitionSha256",ruleHash);
        child.set("actor",parsed.childActor());child.set("parent",parsed.parent());child.set("execution",definition);child.set("parameters",JSON.createObjectNode());
        child.set("approval",JSON.valueToTree(Map.of("uid",3,"username","approver","accessVersion",4,"revision",2,"definitionSha256",ruleHash)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),16);server.setExecutor(handlers);
        server.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getRawPath();
            String target=switch(path){case "/internal/v2/executions/lookup"->"quality";
                case WorkflowQualityParent.LOOKUP_PATH->"workflow";case "/internal/v2/permissions/fixed-execution-authorize"->"auth";
                default->throw new AssertionError("Unexpected peer path");};
            String operation=switch(target){case "quality"->"quality.execution.lookup";case "workflow"->WorkflowQualityParent.LOOKUP_OPERATION;default->"auth.fixed-execution.authorize";};
            assertEquals("POST",exchange.getRequestMethod());assertNull(exchange.getRequestURI().getRawQuery());
            byte[] raw=exchange.getRequestBody().readNBytes(65537);assertTrue(raw.length<=65536);
            for(String header:List.of(SignedHttpClient.USER_PROOF,"X-Internal-Token","Authorization","Cookie"))assertNull(exchange.getRequestHeaders().getFirst(header));
            var request=keys.verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                    new ExpectedRequest(target,"POST",path,BodyHash.sha256(raw),operation,BodyHash.userProof(null)));
            assertEquals("metadata",request.caller());var body=parse(raw);ObjectNode response;int status=200;
            if(target.equals("quality")){
                qualityLookups.incrementAndGet();fields(body,"resultId","fence");assertEquals(11,number(body,"resultId"));assertEquals(1,number(body,"fence"));
                response=JSON.createObjectNode().put("canonicalManifest",string(child)).put("manifestSha256",BodyHash.sha256(bytes(child)))
                        .put("status","running").put("leaseExpiresAt",keys.clock.instant().getEpochSecond()+120);
            }else if(target.equals("workflow")){
                parentLookups.incrementAndGet();fields(body,"instanceId","attemptId","fence","executionRevision","manifestSha256");lastParentRequest.set(body.deepCopy());
                status=parentHttpStatus;response=JSON.createObjectNode().put("canonicalManifest",string(parent)).put("manifestSha256",BodyHash.sha256(bytes(parent)))
                        .put("status",parentState).put("leaseExpiresAt",parentLease==null?keys.clock.instant().getEpochSecond()+1860:parentLease);
            }else{
                authChecks.incrementAndGet();fields(body,"schemaVersion","binding","actor","approval","targets","parent");lastAuthRequest.set(body.deepCopy());
                response=JSON.createObjectNode();response.set("binding",body.get("binding"));response.put("allowed",deniedCheck<0);
                if(deniedCheck<0)response.putNull("reason");else response.put("reason","PERMISSION_DENIED");
                ArrayNode checks=response.putArray("checks");
                checks.add(check("actor","quality_rule",body.get("binding").get("resourceId").longValue(),"execute",0));
                checks.add(check("approval","datasource",body.get("targets").get("sourceDatasourceId").longValue(),"query",1));
                checks.add(check("actor","workflow_task",body.get("parent").get("resourceId").longValue(),"execute",2));
                checks.add(check("parentApproval","quality_rule",body.get("binding").get("resourceId").longValue(),"execute",3));
                response=authResponse.apply(response);
            }
            if(faultTarget.equals(target)&&fault.equals("disconnect"))return;
            byte[] responseBytes=bytes(response);long now=keys.clock.instant().getEpochSecond();
            if(!(faultTarget.equals(target)&&fault.equals("unsigned"))){
                String nonce=faultTarget.equals(target)&&fault.equals("wrong-nonce")?Nonce.random():request.proof().nonce();
                exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,keys.workload(target).sign(
                        new WorkloadResponseProof(2,target,"metadata",nonce,status,BodyHash.sha256(responseBytes),now,now+30)));
            }
            if(faultTarget.equals(target)&&fault.equals("tampered"))responseBytes=bytes(Map.of());
            exchange.sendResponseHeaders(status,responseBytes.length);exchange.getResponseBody().write(responseBytes);
        }catch(Throwable error){failure.compareAndSet(null,error);}finally{exchange.close();}});server.start();
        URI origin=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        var limits=new SignedHttpClient.Limits(1024*1024,1024*1024,8,Duration.ofMillis(500),Duration.ofSeconds(2));
        source=transport("quality","quality.execution.lookup",origin,limits);auth=transport("auth","auth.fixed-execution.authorize",origin,limits);
        workflow=transport("workflow",WorkflowQualityParent.LOOKUP_OPERATION,origin,limits);peers=new FixedQualityPeers(source,auth,workflow,keys.clock);
    }
    private ObjectNode check(String subject,String type,long id,String action,int index){return JSON.createObjectNode().put("subject",subject)
            .put("resourceType",type).put("resourceId",id).put("action",action).put("allowed",deniedCheck!=index);}
    private SignedHttpClient transport(String receiver,String operation,URI origin,SignedHttpClient.Limits limits){return new SignedHttpClient("metadata",
            keys.workload("metadata"),keys.verifier,keys.clock,TimePolicy.defaults(),Map.of(receiver,origin),Map.of(receiver,Set.of(operation)),limits);}
    FixedExecutionAdapter.Submission input(){return new FixedExecutionAdapter.Submission(11,0,1,number(child,"executionRevision"),BodyHash.sha256(bytes(child)));}
    FixedQualityManifest manifest(){return FixedQualityManifest.parse(string(child),input().manifestSha256(),input());}
    @Override public void close() throws Exception{
        int port=server.getAddress().getPort();source.close();auth.close();workflow.close();server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));
        try(var probe=new Socket()){assertThrows(java.io.IOException.class,()->probe.connect(new InetSocketAddress("127.0.0.1",port),500));}
        if(failure.get()!=null)throw new AssertionError("Signed child peer failed",failure.get());
    }
}
