package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.controller.FixedTransferController;
import com.aetheris.metadata.service.*;
import com.aetheris.metadata.entity.DataSource;
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

class FixedTransferControllerTest {
    static final String SUBMIT="/internal/v2/executions/transfer/submit",STATUS="/internal/v2/executions/status";
    final FixedReceiptStore store=mock(FixedReceiptStore.class);final DataSourceService sources=mock(DataSourceService.class);
    DataSource ds(long id){var d=new DataSource();d.setId(id);d.setType("postgresql");d.setStatus("active");d.setUsername("reader");d.setPassword("fixture-only");d.setWriteUsername("writer");d.setWritePassword("fixture-only");d.setConnectionRevision(1L);return d;}
    FixedReceiptStore.Row row(FixedTransferManifest m){return new FixedReceiptStore.Row(99,m.binding().runId(),0,1,2,m.sha256(),m.canonical(),"{}","queued",null,0,null,OffsetDateTime.now().plusMinutes(30),null,null);}
    MockMvc mvc(FixedTransferTestPeers f){
        var coordinator=new FixedTransferCoordinator(store,f.peers,new FixedTransferExecution(sources,mock(JdbcService.class)),Duration.ofMinutes(30));
        Set<String> nonce=ConcurrentHashMap.newKeySet();
        return MockMvcBuilders.standaloneSetup(new FixedTransferController(coordinator)).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)))
                .addFilters(f.keys.filter((c,n,e)->nonce.add(c+":"+n),(u,n,a)->{throw new AssertionError("USER forbidden route must not call sessions");})).build();
    }
    MvcResult send(MockMvc mvc,FixedTransferTestPeers f,String caller,String path,String op,byte[] raw,String user,int status)throws Exception{
        String proof=f.keys.request(caller,path,op,raw,user);var nonce=f.keys.verifier.verifyRequest(proof,new ExpectedRequest("metadata","POST",path,BodyHash.sha256(raw),op,BodyHash.userProof(user))).proof().nonce();
        var request=post(path).contentType("application/json").content(raw).header(SignedHttpClient.REQUEST_PROOF,proof);if(user!=null)request.header(SignedHttpClient.USER_PROOF,user);
        var result=mvc.perform(request).andReturn();assertEquals(status,result.getResponse().getStatus());
        if(Set.of(200,202,400,403,404,409,503).contains(status)&&result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF)!=null)
            f.keys.verifier.verifyResponse(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("metadata",caller,nonce,status,BodyHash.sha256(result.getResponse().getContentAsByteArray())));
        return result;
    }
    @Test void submitAndStatusAreSignedAndExplicitlyIncludeNullableFields()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var m=f.install(11,5,6,"SELECT 1","business.output",s->s);var row=row(m);when(sources.getById(5L)).thenReturn(ds(5));when(sources.getById(6L)).thenReturn(ds(6));
            when(store.find(FixedExecutionOperation.TRANSFER,11,0,1)).thenReturn(Optional.empty());when(store.insert(any(FixedExecutionAdapter.Invocation.class),anyString(),anyString(),any())).thenReturn(row);when(store.receipt(row)).thenCallRealMethod();var mvc=mvc(f);
            var result=send(mvc,f,"ingestion",SUBMIT,FixedTransferManifest.OPERATION,bytes(new FixedTransferManifest.Submit(11,0,1,2,m.sha256())),null,202);
            var out=parse(result.getResponse().getContentAsByteArray());fields(out,"receiptId","origin","operation","runId","attemptId","fence","state","result","errorCode");assertTrue(out.get("result").isNull());assertTrue(out.get("errorCode").isNull());assertNotNull(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF));
            when(store.find(FixedExecutionOperation.TRANSFER,11,0,1)).thenReturn(Optional.of(row));
            var status=send(mvc,f,"ingestion",STATUS,"metadata.fixed-execution.status",bytes(status(null)),null,200);assertNotNull(status.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF));assertEquals(1,f.lookups.get());
        }
    }
    Map<String,Object> status(Long receipt){var n=new LinkedHashMap<String,Object>();n.put("receiptId",receipt);n.put("runId",11);n.put("attemptId",0);n.put("fence",1);n.put("operation",FixedTransferManifest.OPERATION);return n;}
    @Test void strictDtosCannotInjectSqlActorUrlOrCoerceNumbers()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var mvc=mvc(f);String normal=string(new FixedTransferManifest.Submit(11,0,1,2,"a".repeat(64)));
            for(String invalid:List.of("{}",normal+" {}",normal.replace("\"runId\":11","\"runId\":true"),normal.replace("\"runId\":11","\"runId\":11.0"),normal.replace("\"runId\":11","\"runId\":\"11\""),normal.replace("\"runId\":11","\"runId\":9223372036854775808"),normal.replace("\"fence\":1","\"fence\":1,\"fence\":1"),normal.substring(0,normal.length()-1)+",\"sql\":\"DELETE FROM output\"}",normal.substring(0,normal.length()-1)+",\"actor\":\"admin\"}",normal.substring(0,normal.length()-1)+",\"url\":\"http://other\"}"))send(mvc,f,"ingestion",SUBMIT,FixedTransferManifest.OPERATION,invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,400);
            send(mvc,f,"ingestion",SUBMIT,FixedTransferManifest.OPERATION,new byte[65537],null,400);
            for(String invalid:List.of("{}","{\"receiptId\":null,\"runId\":11,\"attemptId\":0,\"fence\":1,\"operation\":\"metadata.fixed-sql.submit\"}",string(status(null))+" {}"))send(mvc,f,"ingestion",STATUS,"metadata.fixed-execution.status",invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8),null,400);
            verifyNoInteractions(store);assertEquals(0,f.lookups.get());
        }
    }
    @Test void callerOperationUserAndAdjacentPathsDoNotExpand()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var mvc=mvc(f);byte[] raw=bytes(status(null));
            // Workflow owns SQL status only; a transfer-shaped tuple never reaches the store.
            send(mvc,f,"workflow",STATUS,"metadata.fixed-execution.status",raw,null,400);
            send(mvc,f,"serving",STATUS,"metadata.fixed-execution.status",raw,null,403);
            send(mvc,f,"ingestion",STATUS,"metadata.fixed-transfer.submit",raw,null,401);
            send(mvc,f,"ingestion",STATUS,"metadata.fixed-execution.status",raw,f.keys.user("ingestion"),403);
            assertEquals(401,mvc.perform(post(STATUS).header("X-Internal-Token","retired").content(raw)).andReturn().getResponse().getStatus());
            var routes=com.aetheris.metadata.config.MetadataV2SecurityConfiguration.workloadRoutes();assertTrue(routes.resolve("GET",STATUS).isEmpty());assertTrue(routes.resolve("POST",STATUS+"/extra").isEmpty());assertTrue(routes.resolve("POST","/internal/ingestion/execute").isEmpty());
            assertEquals(com.aetheris.security.servlet.RoutePolicy.UserMode.REQUIRED,routes.resolve("POST","/internal/v2/agent/query").orElseThrow().userMode());verifyNoInteractions(store);
        }
    }
    @Test void conflictsMissingAndStoreFailuresKeepSignedSafeStatus()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var mvc=mvc(f);when(store.find(FixedExecutionOperation.TRANSFER,11,0,1)).thenReturn(Optional.empty());send(mvc,f,"ingestion",STATUS,"metadata.fixed-execution.status",bytes(status(null)),null,404);
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("do not expose password or sql")).when(store).find(FixedExecutionOperation.TRANSFER,11,0,1);
            var r=send(mvc,f,"ingestion",STATUS,"metadata.fixed-execution.status",bytes(status(null)),null,503);assertFalse(r.getResponse().getContentAsString().contains("password"));assertNotNull(r.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF));
        }
    }
}
