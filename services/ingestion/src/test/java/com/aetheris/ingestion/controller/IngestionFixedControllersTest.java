package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.fixedexecution.*;
import com.aetheris.ingestion.service.*;
import com.aetheris.ingestion.security.*;
import com.aetheris.ingestion.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class IngestionFixedControllersTest {
    static final String HASH="a".repeat(64),PATH="/internal/v2/executions/lookup",OP="ingestion.execution.lookup";
    final IngestionExecutionStore store=mock(IngestionExecutionStore.class);final IngestionTaskService tasks=mock(IngestionTaskService.class);
    final IngestionSchedulerService scheduler=mock(IngestionSchedulerService.class);
    IngestionExecutionStore.Definition definition(boolean approved){return new IngestionExecutionStore.Definition(8,2,HASH,
            "{\"schemaVersion\":1,\"sourceDatasourceId\":8,\"targetDatasourceId\":8,\"sourceSql\":\"SELECT 1\",\"targetTable\":\"target.t\",\"writeMode\":\"append\",\"batchSize\":500}",approved?"{}":null,10,0,null,null,"enabled","0 0 2 * * ?");}
    MockMvc mvc(V2IngestionFixture f){return MockMvcBuilders.standaloneSetup(new IngestionApprovalController(store,f.authorization,scheduler,tasks),new InternalExecutionLookupController(store))
            .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()))
            .addMappedInterceptors(new String[]{"/api/**"},new GatewayIdentityInterceptor(f.authorization)).addFilters(f.workload(),f.direct()).build();}
    @Test void approvalUsesActualTypedSubjectAndOnlyUpdateSourceQueryTargetWrite() throws Exception {
        try(var f=new V2IngestionFixture()) {
            var before=definition(false);when(store.definition(8)).thenReturn(before);when(store.executableDefinition(before)).thenCallRealMethod();when(store.approve(eq(before),any())).thenReturn(definition(true));
            var mvc=mvc(f);var response=mvc.perform(post("/api/ingestion/tasks/8/approve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2}")).andReturn();
            assertEquals(200,response.getResponse().getStatus());var out=FixedJson.parse(response.getResponse().getContentAsByteArray(),65536);FixedJson.fields(out,"executionRevision","definitionSha256","approved","bindingGeneration","scheduleGeneration","cronBound");assertTrue(out.get("approved").booleanValue());
            var captured=org.mockito.ArgumentCaptor.forClass(IngestionActor.class);verify(store).approve(eq(before),captured.capture());assertEquals(42,captured.getValue().uid());assertEquals("alice",captured.getValue().username());assertEquals(3,captured.getValue().accessVersion());
            var checks=f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).toList();assertEquals(3,checks.size());
            assertEquals(List.of("update","query","write"),checks.stream().map(r->r.body().get("action").asText()).toList());
            assertTrue(checks.stream().allMatch(r->!r.body().has("username")));assertEquals(10,out.path("scheduleGeneration").asLong());
        }
    }
    @Test void cronBindingOnlyAcceptsExpectedRevisionGenerationAndCurrentUser() throws Exception {
        try(var f=new V2IngestionFixture()) {
            when(store.bindCron(eq(8L),eq(2L),eq(10L),any())).thenReturn(definition(true));var mvc=mvc(f);
            assertEquals(200,mvc.perform(post("/api/ingestion/tasks/8/cron-binding").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2,\"expectedGeneration\":10}")).andReturn().getResponse().getStatus());
            verify(store).bindCron(eq(8L),eq(2L),eq(10L),any());
            when(store.bindCron(eq(8L),eq(2L),eq(9L),any())).thenThrow(IngestionExecutionStore.conflict());
            assertEquals(409,mvc.perform(post("/api/ingestion/tasks/8/cron-binding").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2,\"expectedGeneration\":9}")).andReturn().getResponse().getStatus());
        }
    }
    @Test void approvalBodyCannotChooseActorApprovalOrCoerceRevision() throws Exception {
        try(var f=new V2IngestionFixture()) {
            var mvc=mvc(f);
            for(String body:List.of("{}","{\"expectedRevision\":\"2\"}","{\"expectedRevision\":2.0}","{\"expectedRevision\":true}","{\"expectedRevision\":2,\"expectedRevision\":2}","{\"expectedRevision\":2,\"username\":\"admin\"}","{\"expectedRevision\":2,\"execution_approval\":{}}","{\"expectedRevision\":2} {}"))
                assertEquals(400,mvc.perform(post("/api/ingestion/tasks/8/approve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
            verifyNoInteractions(store);
            f.allowed.set(false);assertEquals(403,mvc.perform(post("/api/ingestion/tasks/8/approve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2}")).andReturn().getResponse().getStatus());
        }
    }
    MvcResult lookup(MockMvc mvc,V2IngestionFixture f,String caller,String raw,String user,int status) throws Exception {
        byte[] body=raw.getBytes(StandardCharsets.UTF_8);long now=f.clock.instant().getEpochSecond();String nonce=Nonce.random();
        String proof=f.signer(caller,KeyPurpose.WORKLOAD,caller).sign(new WorkloadRequestProof(2,caller,"ingestion","POST",PATH,BodyHash.sha256(body),nonce,now,now+30,OP,BodyHash.userProof(user)));
        var request=post(PATH).contentType("application/json").content(body).header(SignedHttpClient.REQUEST_PROOF,proof);if(user!=null)request.header(SignedHttpClient.USER_PROOF,user);
        var result=mvc.perform(request).andReturn();assertEquals(status,result.getResponse().getStatus());
        if(Set.of(200,400,409,503).contains(status))f.verifier.verifyResponse(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("ingestion",caller,nonce,status,BodyHash.sha256(result.getResponse().getContentAsByteArray())));
        return result;
    }
    @Test void metadataOnlyLookupIsSignedStrictAndNeverCreatesAnActorFromBody() throws Exception {
        try(var f=new V2IngestionFixture()) {
            when(store.lookup(11,1)).thenReturn(new IngestionExecutionStore.Lookup("{\"sealed\":true}",HASH,"running",1800000000));var mvc=mvc(f);
            var good=lookup(mvc,f,"metadata","{\"runId\":11,\"fence\":1}",null,200);FixedJson.fields(FixedJson.parse(good.getResponse().getContentAsByteArray(),65536),"canonicalManifest","manifestSha256","status","leaseExpiresAt");
            for(String body:List.of("{}","{\"runId\":11,\"fence\":2}","{\"runId\":\"11\",\"fence\":1}","{\"runId\":11,\"fence\":1,\"actor\":\"admin\"}","{\"runId\":11,\"fence\":1,\"fence\":1}"))lookup(mvc,f,"metadata",body,null,400);
            lookup(mvc,f,"system","{\"runId\":11,\"fence\":1}",null,403);lookup(mvc,f,"metadata","{\"runId\":11,\"fence\":1}",f.token(),403);
            assertTrue(f.requests.isEmpty());verify(store,times(1)).lookup(11,1);
        }
    }
    @Test void staleSourceAndDatabaseFailureRemainSignedConflictOrUnavailable() throws Exception {
        try(var f=new V2IngestionFixture()) {
            var mvc=mvc(f);when(store.lookup(11,1)).thenThrow(IngestionExecutionStore.conflict());lookup(mvc,f,"metadata","{\"runId\":11,\"fence\":1}",null,409);
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL")).when(store).lookup(11,1);
            assertFalse(lookup(mvc,f,"metadata","{\"runId\":11,\"fence\":1}",null,503).getResponse().getContentAsString().contains("private"));
        }
    }
}
