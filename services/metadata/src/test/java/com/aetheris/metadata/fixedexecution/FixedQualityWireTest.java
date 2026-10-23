package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.controller.FixedTransferController;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static com.aetheris.metadata.fixedexecution.FixedJson.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class FixedQualityWireTest {
    static final String PATH="/internal/v2/executions/quality-query/submit",STATUS="/internal/v2/executions/status",OP="metadata.fixed-quality-query.submit";
    final FixedReceiptStore store=mock(FixedReceiptStore.class);final DataSourceService sources=mock(DataSourceService.class);
    FixedExecutionAdapter.Submission input(FixedQualityManifest m){return new FixedExecutionAdapter.Submission(m.binding().runId(),0,1,2,m.sha256());}
    @Test void lookupUsesResultIdAndExactlyTwoBoundChecksWithoutUserOrWriteScope()throws Exception{
        try(var f=new FixedQualityTestPeers()){
            var m=f.install(11,5,"sql","SELECT COUNT(*) FROM business.input",null,s->s);var resolved=f.peers.resolveAndAuthorize(input(m));
            assertEquals("SELECT COUNT(*) FROM business.input",resolved.definition().sql());assertEquals(0,resolved.definition().threshold());assertEquals(1,f.lookups.get());assertEquals(1,f.checks.get());
            for(String fault:List.of("wrong-binding","missing-check","unsigned","tampered")){f.fault=fault;assertThrows(FixedTransferPeers.Unavailable.class,()->f.peers.resolveAndAuthorize(input(m)));}
            f.fault="inactive";assertThrows(FixedTransferPeers.Rejected.class,()->f.peers.resolveAndAuthorize(input(m)));f.fault="";f.allowed.set(false);assertThrows(FixedTransferPeers.Rejected.class,()->f.peers.resolveAndAuthorize(input(m)));
            f.allowed.set(true);f.sourceActive.set(false);assertThrows(FixedTransferPeers.Rejected.class,()->f.peers.resolveAndAuthorize(input(m)));f.sourceActive.set(true);
            f.faultOnSource=true;for(String fault:List.of("unsigned","tampered")){f.fault=fault;assertThrows(FixedTransferPeers.Unavailable.class,()->f.peers.resolveAndAuthorize(input(m)));}
        }
    }
    MockMvc mvc(FixedQualityTestPeers f){
        var directory=new FixedExecutionAdapters(mock(FixedTransferPeers.class),mock(FixedTransferExecution.class),f.peers,new FixedQualityExecution(sources,mock(JdbcService.class)));
        var coordinator=new FixedTransferCoordinator(store,directory,Duration.ofMinutes(30));Set<String> nonces=ConcurrentHashMap.newKeySet();
        return MockMvcBuilders.standaloneSetup(new FixedTransferController(coordinator)).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter())
                .addFilters(f.keys.filter((c,n,e)->nonces.add(c+":"+n),(u,n,a)->{throw new AssertionError("USER forbidden");})).build();
    }
    MvcResult call(MockMvc mvc,FixedQualityTestPeers f,String caller,String path,String op,byte[] raw,String user,int expected)throws Exception{
        String proof=f.keys.request(caller,path,op,raw,user);var nonce=f.keys.verifier.verifyRequest(proof,new ExpectedRequest("metadata","POST",path,BodyHash.sha256(raw),op,BodyHash.userProof(user))).proof().nonce();
        var request=post(path).contentType("application/json").content(raw).header(SignedHttpClient.REQUEST_PROOF,proof);if(user!=null)request.header(SignedHttpClient.USER_PROOF,user);
        var result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus());
        if(Set.of(200,202,400,404,409,503).contains(expected)){assertNotNull(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF));f.keys.verifier.verifyResponse(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("metadata",caller,nonce,expected,BodyHash.sha256(result.getResponse().getContentAsByteArray())));}return result;
    }
    static byte[] status(long run,String operation){var body=new LinkedHashMap<String,Object>();body.put("receiptId",null);body.put("runId",run);body.put("attemptId",0);body.put("fence",1);body.put("operation",operation);return bytes(body);}
    @Test void signedSubmitAndStatusUseReadOnlyDatasourceAndStrictTwoFieldResult()throws Exception{
        try(var f=new FixedQualityTestPeers()){
            var m=f.install(11,5,"sql","SELECT COUNT(*) FROM business.input",0,s->s);var d=new DataSource();d.setId(5L);d.setUsername("reader");d.setPassword("fixture-only");d.setConnectionRevision(0L);d.setStatus("active");when(sources.getById(5L)).thenReturn(d);
            var queued=new FixedReceiptStore.Row(99,11,0,1,2,m.sha256(),m.canonical(),"{}","queued",null,0,null,OffsetDateTime.now().plusMinutes(30),null,null,FixedExecutionOperation.QUALITY);
            when(store.find(FixedExecutionOperation.QUALITY,11,0,1)).thenReturn(Optional.empty());when(store.insert(any(FixedExecutionAdapter.Invocation.class),anyString(),anyString(),any())).thenReturn(queued);when(store.receipt(any())).thenCallRealMethod();var mvc=mvc(f);
            var submit=call(mvc,f,"quality",PATH,OP,bytes(input(m)),null,202);assertTrue(parse(submit.getResponse().getContentAsByteArray()).get("result").isNull());
            var done=new FixedReceiptStore.Row(99,11,0,1,2,m.sha256(),m.canonical(),"{}","succeeded","owner",1,OffsetDateTime.now().plusSeconds(120),OffsetDateTime.now().plusMinutes(30),"{\"violationCount\":2,\"durationMs\":3}",null,FixedExecutionOperation.QUALITY);
            when(store.find(FixedExecutionOperation.QUALITY,11,0,1)).thenReturn(Optional.of(done));var result=parse(call(mvc,f,"quality",STATUS,"metadata.fixed-execution.status",status(11,OP),null,200).getResponse().getContentAsByteArray());fields(result.get("result"),"violationCount","durationMs");assertEquals(2,number(result.get("result"),"violationCount"));
            call(mvc,f,"ingestion",STATUS,"metadata.fixed-execution.status",status(11,OP),null,400);
        }
    }
    @Test void submitCannotChooseActorSqlCallerOrAParentAndWrongWorkloadCannotReadQualityStatus()throws Exception{
        try(var f=new FixedQualityTestPeers()){
            var mvc=mvc(f);var m=f.install(11,5,"sql","SELECT COUNT(*) FROM business.input",0,s->s);String normal=string(input(m));
            for(String raw:List.of(normal+" {}",normal.replace("\"runId\":11","\"runId\":true"),normal.replace("\"runId\":11","\"runId\":11.0"),normal.replace("\"fence\":1","\"fence\":1,\"fence\":1"),normal.substring(0,normal.length()-1)+",\"sql\":\"SELECT 1\"}",normal.substring(0,normal.length()-1)+",\"actor\":\"admin\"}",normal.substring(0,normal.length()-1)+",\"parent\":{}}"))call(mvc,f,"quality",PATH,OP,raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,400);
            call(mvc,f,"ingestion",PATH,OP,bytes(input(m)),null,403);call(mvc,f,"quality",PATH,OP,bytes(input(m)),f.keys.user("quality"),403);call(mvc,f,"quality",PATH,"metadata.fixed-transfer.submit",bytes(input(m)),null,401);
            assertEquals(401,mvc.perform(post(PATH).header("X-Internal-Token","retired").content(bytes(input(m)))).andReturn().getResponse().getStatus());verifyNoInteractions(store);
        }
    }
    @Test void manifestRefusesParentPipelineThresholdAndDefinitionReplacement()throws Exception{
        try(var f=new FixedQualityTestPeers()){
            var m=f.install(11,5,"sql","SELECT COUNT(*) FROM business.input",0,s->s);
            for(String kind:List.of("pipeline","workflow-child")){var n=(com.fasterxml.jackson.databind.node.ObjectNode)parse(m.canonical());((com.fasterxml.jackson.databind.node.ObjectNode)n.get("actor")).put("kind",kind);reject(n);}
            var n=(com.fasterxml.jackson.databind.node.ObjectNode)parse(m.canonical());n.set("parent",JSON.createObjectNode());reject(n);
            for(Object threshold:List.of(-1,2147483648L,"1",1.0,true)){n=(com.fasterxml.jackson.databind.node.ObjectNode)parse(m.canonical());((com.fasterxml.jackson.databind.node.ObjectNode)n.get("execution")).set("threshold",JSON.valueToTree(threshold));reject(n);}
        }
    }
    void reject(com.fasterxml.jackson.databind.JsonNode n){String definition=FixedTransferManifest.definitionHash(n.get("execution"));((com.fasterxml.jackson.databind.node.ObjectNode)n).put("definitionSha256",definition);((com.fasterxml.jackson.databind.node.ObjectNode)n.get("approval")).put("definitionSha256",definition);String canonical=string(n),hash=BodyHash.sha256(bytes(n));assertThrows(IllegalArgumentException.class,()->FixedQualityManifest.parse(canonical,hash,new FixedExecutionAdapter.Submission(11,0,1,2,hash)));}
}
