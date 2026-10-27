package com.aetheris.metadata.fixedexecution;

import com.aetheris.security.protocol.BodyHash;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FixedQualityChildPeersTest {
    @Test void allParentActorsRequireFreshSignedParentAndExactlyFourChecks() throws Exception {
        for(String kind:List.of("manual","cron","pipeline"))try(var fixture=new FixedQualityChildTestPeers(kind)){
            var manifest=fixture.peers.resolveAndAuthorize(fixture.input());
            assertTrue(manifest.workflowChild());assertEquals("workflow-child",manifest.actor().kind());
            assertEquals(parse(bytes(manifest.parentReference())),fixture.lastParentRequest.get());
            assertEquals(parse(bytes(manifest.authorizationRequest())),fixture.lastAuthRequest.get());
            assertEquals(8,fixture.lastAuthRequest.get().get("parent").get("approval").get("accessVersion").asLong());
            assertEquals(manifest.canonical(),fixture.peers.resolveAndAuthorize(fixture.input()).canonical());
            assertEquals(2,fixture.qualityLookups.get());assertEquals(2,fixture.parentLookups.get());assertEquals(2,fixture.authChecks.get());
        }
    }
    @Test void parentAccessorsCannotMutateSealedAuthorization() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            var manifest=fixture.manifest();var expected=parse(bytes(manifest.authorizationRequest()));
            ((ObjectNode)manifest.parent()).put("runId",999);
            ((ObjectNode)manifest.authorizationRequest().get("parent")).put("fence",999);
            assertEquals(expected,parse(bytes(manifest.authorizationRequest())));assertEquals(901,manifest.parentReference().instanceId());
        }
    }
    @Test void manualAndCronCannotAcquireAChildParentAndChildCannotOmitOne() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            for(String kind:List.of("manual","cron","pipeline")){
                var body=fixture.child.deepCopy();((ObjectNode)body.get("actor")).put("kind",kind);reject(body);
            }
            var body=fixture.child.deepCopy();body.putNull("parent");reject(body);
        }
    }
    @Test void childParentUsesStrictSixFieldsAndTypedBoundIdentity() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            List<Consumer<ObjectNode>> mutations=List.of(
                    p->p.remove("approval"),p->p.put("url","http://invalid"),p->p.put("resourceId",0),p->p.put("runId",true),
                    p->p.put("attemptId",1.5),p->p.put("fence","3"),p->p.put("manifestSha256","A".repeat(64)),
                    p->((ObjectNode)p.get("approval")).put("revision",0),p->((ObjectNode)p.get("approval")).put("uid",0),
                    p->((ObjectNode)p.get("approval")).put("accessVersion",-1),p->((ObjectNode)p.get("approval")).put("username","bad user"),
                    p->((ObjectNode)p.get("approval")).put("definitionSha256","invalid"),p->((ObjectNode)p.get("approval")).put("extra",1));
            for(var mutation:mutations){var body=fixture.child.deepCopy();mutation.accept((ObjectNode)body.get("parent"));reject(body);}
            String normal=string(fixture.child);String duplicate=normal.replace("\"runId\":901","\"runId\":901,\"runId\":901");reject(duplicate);reject(normal+" {}");
        }
    }
    @Test void wrongParentTupleApprovalAndActorFailBeforeAuthorization() throws Exception {
        for(String field:List.of("resourceId","runId","attemptId","fence","manifestSha256","approval-uid","approval-username","approval-accessVersion","approval-revision","approval-definitionSha256","actor-uid","actor-username","actor-accessVersion","actor-requestId"))
            try(var fixture=new FixedQualityChildTestPeers()){
                var parent=(ObjectNode)fixture.child.get("parent");
                if(field.startsWith("approval-")){
                    String key=field.substring(9);var approval=(ObjectNode)parent.get("approval");
                    if(key.equals("username"))approval.put(key,"other_approver");else if(key.equals("definitionSha256"))approval.put(key,"b".repeat(64));else approval.put(key,approval.get(key).longValue()+1);
                }else if(field.startsWith("actor-")){
                    String key=field.substring(6);var actor=(ObjectNode)fixture.child.get("actor");
                    if(key.equals("username"))actor.put(key,"other_operator");else if(key.equals("requestId"))actor.put(key,"other-request");else actor.put(key,actor.get(key).longValue()+1);
                }else if(field.equals("manifestSha256"))parent.put(field,"b".repeat(64));else parent.put(field,parent.get(field).longValue()+1);
                assertThrows(FixedTransferPeers.Unavailable.class,()->fixture.peers.resolveAndAuthorize(fixture.input()),field);assertEquals(0,fixture.authChecks.get(),field);
            }
    }
    @Test void childRuleRevisionAndDefinitionCannotReplaceFrozenParentSelection() throws Exception {
        for(String change:List.of("rule","revision","definition"))try(var fixture=new FixedQualityChildTestPeers()){
            if(change.equals("rule"))fixture.child.put("resourceId",10);
            else if(change.equals("revision")){fixture.child.put("executionRevision",3);((ObjectNode)fixture.child.get("approval")).put("revision",3);}
            else{
                ((ObjectNode)fixture.child.get("execution")).put("expression","SELECT COUNT(*) FROM business.other");
                String hash=FixedTransferManifest.definitionHash(fixture.child.get("execution"));fixture.child.put("definitionSha256",hash);((ObjectNode)fixture.child.get("approval")).put("definitionSha256",hash);
            }
            assertThrows(FixedTransferPeers.Unavailable.class,()->fixture.peers.resolveAndAuthorize(fixture.input()),change);assertEquals(0,fixture.authChecks.get());
        }
    }
    @Test void changedOrMalformedCanonicalParentAndHashFailClosed() throws Exception {
        for(String change:List.of("actor","approval","definition","type","extra","hash"))try(var fixture=new FixedQualityChildTestPeers()){
            switch(change){case "actor","hash"->((ObjectNode)fixture.parent.get("actor")).put("uid",99);
                case "approval"->((ObjectNode)fixture.parent.get("approval")).put("uid",99);
                case "definition"->((ObjectNode)fixture.parent.get("execution")).put("qualityExecutionRevision",3);
                case "type"->fixture.parent.put("attemptId",true);case "extra"->fixture.parent.put("url","http://invalid");}
            if(!change.equals("hash"))((ObjectNode)fixture.child.get("parent")).put("manifestSha256",BodyHash.sha256(bytes(fixture.parent)));
            assertThrows(FixedTransferPeers.Unavailable.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));assertEquals(0,fixture.authChecks.get());
        }
    }
    @Test void inactiveExpiredOversizedLeaseAndDeniedParentAreRejected() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            fixture.peers.resolveAndAuthorize(fixture.input());fixture.parentState="unknown";
            assertThrows(FixedTransferPeers.Rejected.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));fixture.parentState="running";
            fixture.parentLease=fixture.keys.clock.instant().getEpochSecond()+4;
            assertThrows(FixedTransferPeers.Rejected.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));
            fixture.parentLease=fixture.keys.clock.instant().getEpochSecond()+8000;
            assertThrows(FixedTransferPeers.Rejected.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));fixture.parentLease=null;
            for(int status:List.of(403,404,409)){fixture.parentHttpStatus=status;assertThrows(FixedTransferPeers.Rejected.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));}
            assertEquals(1,fixture.authChecks.get());
        }
    }
    @Test void eachOfFourCurrentPermissionsCanRejectChildExecution() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            for(int index=0;index<4;index++){fixture.deniedCheck=index;assertThrows(FixedTransferPeers.Rejected.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));}
            assertEquals(4,fixture.authChecks.get());assertEquals(4,fixture.parentLookups.get());
            fixture.deniedCheck=-1;fixture.authResponse=body->{body.put("allowed",false).put("reason","SUBJECT_INACTIVE");((ArrayNode)body.get("checks")).removeAll();return body;};
            assertThrows(FixedTransferPeers.Rejected.class,()->fixture.peers.resolveAndAuthorize(fixture.input()));
        }
    }
    @Test void malformedAuthorizationSetsCannotWidenOrReorderChildPermissions() throws Exception {
        for(String change:List.of("missing","extra","duplicate","reorder","subject","resource","action","allowed","denied-all-true","binding"))try(var fixture=new FixedQualityChildTestPeers()){
            fixture.authResponse=body->{var checks=(ArrayNode)body.get("checks");switch(change){
                case "missing"->checks.remove(3);case "extra"->checks.add(checks.get(0).deepCopy());case "duplicate"->checks.set(3,checks.get(2).deepCopy());
                case "reorder"->{var first=checks.get(2);checks.set(2,checks.get(3));checks.set(3,first);}
                case "subject"->((ObjectNode)checks.get(3)).put("subject","approval");case "resource"->((ObjectNode)checks.get(2)).put("resourceId",999);
                case "action"->((ObjectNode)checks.get(1)).put("action","write");case "allowed"->((ObjectNode)checks.get(0)).put("allowed",false);
                case "denied-all-true"->body.put("allowed",false).put("reason","PERMISSION_DENIED");case "binding"->((ObjectNode)body.get("binding")).put("runId",99);}
                return body;};
            assertThrows(FixedTransferPeers.Unavailable.class,()->fixture.peers.resolveAndAuthorize(fixture.input()),change);
        }
    }
    @Test void unsignedTamperedDisconnectedAndUnboundResponsesFailClosedAtEachPeer() throws Exception {
        for(String target:List.of("quality","workflow","auth"))for(String fault:List.of("unsigned","tampered","disconnect","wrong-nonce"))try(var fixture=new FixedQualityChildTestPeers()){
            fixture.faultTarget=target;fixture.fault=fault;assertThrows(FixedTransferPeers.Unavailable.class,()->fixture.peers.resolveAndAuthorize(fixture.input()),target+":"+fault);
        }
    }
    @Test void threeArgumentCompatibilityConstructorCannotExecuteAChildWithoutParentTransport() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            var peers=new FixedQualityPeers(fixture.source,fixture.auth,fixture.keys.clock);
            assertThrows(FixedTransferPeers.Unavailable.class,()->peers.resolveAndAuthorize(fixture.input()));assertEquals(0,fixture.parentLookups.get());assertEquals(0,fixture.authChecks.get());
        }
    }
    @Test void queuedWorkerRechecksParentBeforeAnyPhysicalQualityQuery() throws Exception {
        try(var fixture=new FixedQualityChildTestPeers()){
            var manifest=fixture.peers.resolveAndAuthorize(fixture.input());fixture.parentState="unknown";
            var store=mock(FixedReceiptStore.class);var execution=mock(FixedQualityExecution.class);
            var row=new FixedReceiptStore.Row(99,11,0,1,2,manifest.sha256(),manifest.canonical(),"{}","executing","fixture",1,
                    OffsetDateTime.now().plusSeconds(120),OffsetDateTime.now().plusMinutes(30),null,null,FixedExecutionOperation.QUALITY);
            when(store.queued(anyInt())).thenReturn(List.of(99L));when(store.claim(eq(99L),anyString())).thenReturn(Optional.of(row));when(store.live(row)).thenReturn(true);
            var adapters=new FixedExecutionAdapters(mock(FixedTransferPeers.class),mock(FixedTransferExecution.class),fixture.peers,execution);
            try(var worker=new FixedTransferWorker(store,adapters,1,fixture.keys.clock)){worker.tick();FixedTransferWorkerTest.until(()->worker.physicalActive()==0);}
            verifyNoInteractions(execution);verify(store).finish(eq(row),eq("failed"),isNull(),eq("SOURCE_NOT_ACTIVE"));assertEquals(2,fixture.parentLookups.get());assertEquals(1,fixture.authChecks.get());
        }
    }
    private static void reject(ObjectNode body){reject(string(body));}
    private static void reject(String canonical){String hash=BodyHash.sha256(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class,()->FixedQualityManifest.parse(canonical,hash,new FixedExecutionAdapter.Submission(11,0,1,2,hash)));}
}
