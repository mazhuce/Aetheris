package com.aetheris.metadata.fixedexecution;
import com.aetheris.metadata.controller.FixedSqlController;
import com.aetheris.metadata.controller.FixedTransferController;
import com.aetheris.metadata.security.MetadataProofFixture;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class FixedSqlControllerTest {
    static final String PATH="/internal/v2/sql/validate-write",OP="metadata.fixed-sql.validate-write";
    MvcResult post(MockMvc mvc,MetadataProofFixture p,String path,String op,String caller,String raw,String user,int expected)throws Exception{
        byte[] bytes=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);String nonce=Nonce.random();long now=p.clock.instant().getEpochSecond();String proof=p.workload(caller).sign(new WorkloadRequestProof(2,caller,"metadata","POST",path,BodyHash.sha256(bytes),nonce,now,now+30,op,BodyHash.userProof(user)));
        var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(bytes).header(SignedHttpClient.REQUEST_PROOF,proof);if(user!=null)request.header(SignedHttpClient.USER_PROOF,user);
        var result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus());if(expected!=403)p.verifier.verifyResponse(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("metadata",caller,nonce,expected,BodyHash.sha256(result.getResponse().getContentAsByteArray())));return result;
    }
    @Test void pureValidationIsSignedStrictAndCannotReachCoordinatorOrJdbc()throws Exception{
        var p=new MetadataProofFixture();var coordinator=mock(FixedTransferCoordinator.class);var mvc=MockMvcBuilders.standaloneSetup(new FixedSqlController(coordinator)).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter()).addFilters(p.filter((c,n,e)->true,(u,n,v)->{throw new AssertionError("No USER permitted");})).build();
        var valid=post(mvc,p,PATH,OP,"workflow","{\"sql\":\"INSERT INTO t SELECT 1\"}",null,200);var n=FixedJson.parse(valid.getResponse().getContentAsByteArray());FixedJson.fields(n,"valid","reason");assertTrue(n.get("valid").booleanValue());assertTrue(n.get("reason").isNull());
        for(String sql:List.of("SELECT 1","UPDATE t SET x=1","DELETE FROM t","INSERT INTO t SELECT 1; SELECT 1","CREATE TABLE t AS SELECT pg_read_file('/x')")){
            var rejected=post(mvc,p,PATH,OP,"workflow",FixedJson.string(Map.of("sql",sql)),null,200);var out=FixedJson.parse(rejected.getResponse().getContentAsByteArray());assertFalse(out.get("valid").booleanValue());assertEquals("SQL_POLICY_REJECTED",out.get("reason").textValue());
        }
        for(String body:List.of("{}","{\"sql\":1}","{\"sql\":\"SELECT 1\",\"sql\":\"SELECT 1\"}","{\"sql\":\"SELECT 1\",\"datasourceId\":8}","{\"sql\":\"SELECT 1\"} {}"))post(mvc,p,PATH,OP,"workflow",body,null,400);
        post(mvc,p,PATH,OP,"quality","{\"sql\":\"INSERT INTO t SELECT 1\"}",null,403);post(mvc,p,PATH,OP,"workflow","{\"sql\":\"INSERT INTO t SELECT 1\"}",p.user("workflow"),403);
        var old=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/sql/validate-write").header("X-Internal-Token","legacy").contentType("application/json").content("{\"sql\":\"INSERT INTO t SELECT 1\"}")).andReturn();assertEquals(400,old.getResponse().getStatus());assertNull(old.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF));verifyNoInteractions(coordinator);
    }
    @Test void sqlSubmitAndSharedStatusKeepPositiveTupleCallerAndSignedNullableResult()throws Exception{
        var p=new MetadataProofFixture();var store=mock(FixedReceiptStore.class);var adapters=mock(FixedExecutionAdapters.class);String hash="a".repeat(64);
        var row=new FixedReceiptStore.Row(99,11,111,3,2,hash,"{}","{}","succeeded","worker",1,null,java.time.OffsetDateTime.now().plusMinutes(1),"{\"affectedRows\":null,\"durationMs\":2}",null,FixedExecutionOperation.SQL);
        when(store.find(FixedExecutionOperation.SQL,11,111,3)).thenReturn(Optional.of(row));when(store.requireCompatible(eq(row),eq(FixedExecutionOperation.SQL),any())).thenCallRealMethod();when(store.receipt(row)).thenCallRealMethod();
        var co=new FixedTransferCoordinator(store,adapters,java.time.Duration.ofMinutes(30));var mvc=MockMvcBuilders.standaloneSetup(new FixedSqlController(co),new FixedTransferController(co)).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter()).addFilters(p.filter((c,n,e)->true,(u,n,v)->{throw new AssertionError("No USER permitted");})).build();
        String submit=FixedJson.string(new FixedExecutionAdapter.Submission(11,111,3,2,hash));var result=post(mvc,p,"/internal/v2/executions/sql/submit","metadata.fixed-sql.submit","workflow",submit,null,202);var out=FixedJson.parse(result.getResponse().getContentAsByteArray());assertEquals(9,out.size());assertTrue(out.get("result").get("affectedRows").isNull());
        var status=new LinkedHashMap<String,Object>();status.put("receiptId",99);status.put("runId",11);status.put("attemptId",111);status.put("fence",3);status.put("operation","metadata.fixed-sql.submit");post(mvc,p,"/internal/v2/executions/status","metadata.fixed-execution.status","workflow",FixedJson.string(status),null,200);
        post(mvc,p,"/internal/v2/executions/sql/submit","metadata.fixed-sql.submit","ingestion",submit,null,403);post(mvc,p,"/internal/v2/executions/sql/submit","metadata.fixed-sql.submit","workflow",submit,p.user("workflow"),403);
        post(mvc,p,"/internal/v2/executions/sql/submit","metadata.fixed-sql.submit","workflow",submit.replace("\"attemptId\":111","\"attemptId\":0"),null,400);post(mvc,p,"/internal/v2/executions/status","metadata.fixed-execution.status","ingestion",FixedJson.string(status),null,400);verifyNoInteractions(adapters);
    }
}
