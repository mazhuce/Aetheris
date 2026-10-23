package com.aetheris.workflow.controller;

import com.aetheris.workflow.fixedexecution.*;
import com.aetheris.workflow.service.*;
import com.aetheris.workflow.security.*;
import com.aetheris.workflow.controller.GlobalExceptionHandler;
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

class WorkflowFixedControllersTest {
    static final String HASH="a".repeat(64),PATH="/internal/v2/executions/lookup",OP="workflow.execution.lookup";
    final WorkflowExecutionStore store=mock(WorkflowExecutionStore.class);final TaskService tasks=mock(TaskService.class);
    final TaskSchedulerService scheduler=mock(TaskSchedulerService.class);
    final SqlExecutionClient client=mock(SqlExecutionClient.class);
    WorkflowExecutionStore.Definition definition(boolean approved){return new WorkflowExecutionStore.Definition(8,2,HASH,
            "{\"schemaVersion\":1,\"datasourceId\":8,\"targetTable\":\"ods.orders\",\"ruleType\":\"range\",\"expression\":\"amount >= 0\",\"threshold\":0}",approved?"{}":null,10,0,null,null,"enabled","0 0 2 * * ?");}
    MockMvc mvc(V2WorkflowFixture f){return MockMvcBuilders.standaloneSetup(new WorkflowApprovalController(store,f.authorization,client,scheduler,tasks),new InternalExecutionLookupController(store))
            .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()))
            .addMappedInterceptors(new String[]{"/api/**"},new GatewayIdentityInterceptor(f.authorization)).addFilters(f.workload(),f.direct()).build();}
    @Test void approvalUsesActualTypedSubjectAndOnlyUpdateSourceQueryTargetWrite() throws Exception {
        try(var f=new V2WorkflowFixture()) {
            var before=definition(false);when(store.definition(8)).thenReturn(before);when(store.executableDefinition(before)).thenReturn(FixedJson.parse("{\"datasourceId\":8,\"sql\":\"INSERT INTO target SELECT 1\"}"));when(store.approve(eq(before),any())).thenReturn(definition(true));
            var mvc=mvc(f);var response=mvc.perform(post("/api/workflow/tasks/8/approve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2}")).andReturn();
            assertEquals(200,response.getResponse().getStatus());var out=FixedJson.parse(response.getResponse().getContentAsByteArray(),65536);FixedJson.fields(out,"executionRevision","definitionSha256","approved","bindingGeneration","scheduleGeneration","cronBound");assertTrue(out.get("approved").booleanValue());
            var captured=org.mockito.ArgumentCaptor.forClass(WorkflowActor.class);verify(store).approve(eq(before),captured.capture());assertEquals(42,captured.getValue().uid());assertEquals("alice",captured.getValue().username());assertEquals(3,captured.getValue().accessVersion());
            var checks=f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).toList();assertEquals(3,checks.size());
            assertEquals(List.of("update","query","write"),checks.stream().map(r->r.body().get("action").asText()).toList());
            assertTrue(checks.stream().allMatch(r->!r.body().has("username")));assertEquals(10,out.path("scheduleGeneration").asLong());
        }
    }
    @Test void cronBindingOnlyAcceptsExpectedRevisionGenerationAndCurrentUser() throws Exception {
        try(var f=new V2WorkflowFixture()) {
            when(store.bindCron(eq(8L),eq(2L),eq(10L),any())).thenReturn(definition(true));var mvc=mvc(f);
            assertEquals(200,mvc.perform(post("/api/workflow/tasks/8/cron-binding").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2,\"expectedGeneration\":10}")).andReturn().getResponse().getStatus());
            verify(store).bindCron(eq(8L),eq(2L),eq(10L),any());
            when(store.bindCron(eq(8L),eq(2L),eq(9L),any())).thenThrow(WorkflowExecutionStore.conflict());
            assertEquals(409,mvc.perform(post("/api/workflow/tasks/8/cron-binding").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2,\"expectedGeneration\":9}")).andReturn().getResponse().getStatus());
        }
    }
    @Test void approvalBodyCannotChooseActorApprovalOrCoerceRevision() throws Exception {
        try(var f=new V2WorkflowFixture()) {
            var mvc=mvc(f);
            for(String body:List.of("{}","{\"expectedRevision\":\"2\"}","{\"expectedRevision\":2.0}","{\"expectedRevision\":true}","{\"expectedRevision\":2,\"expectedRevision\":2}","{\"expectedRevision\":2,\"username\":\"admin\"}","{\"expectedRevision\":2,\"execution_approval\":{}}","{\"expectedRevision\":2} {}"))
                assertEquals(400,mvc.perform(post("/api/workflow/tasks/8/approve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content(body)).andReturn().getResponse().getStatus());
            verifyNoInteractions(store);
            f.allowed.set(false);assertEquals(403,mvc.perform(post("/api/workflow/tasks/8/approve").header(SignedHttpClient.USER_PROOF,f.token()).contentType("application/json").content("{\"expectedRevision\":2}")).andReturn().getResponse().getStatus());
        }
    }
    MvcResult lookup(MockMvc mvc,V2WorkflowFixture f,String caller,String raw,String user,int status) throws Exception {
        byte[] body=raw.getBytes(StandardCharsets.UTF_8);long now=f.clock.instant().getEpochSecond();String nonce=Nonce.random();
        String proof=f.signer(caller,KeyPurpose.WORKLOAD,caller).sign(new WorkloadRequestProof(2,caller,"workflow","POST",PATH,BodyHash.sha256(body),nonce,now,now+30,OP,BodyHash.userProof(user)));
        var request=post(PATH).contentType("application/json").content(body).header(SignedHttpClient.REQUEST_PROOF,proof);if(user!=null)request.header(SignedHttpClient.USER_PROOF,user);
        var result=mvc.perform(request).andReturn();assertEquals(status,result.getResponse().getStatus());
        if(Set.of(200,400,409,503).contains(status))f.verifier.verifyResponse(result.getResponse().getHeader(SignedHttpClient.RESPONSE_PROOF),new ExpectedResponse("workflow",caller,nonce,status,BodyHash.sha256(result.getResponse().getContentAsByteArray())));
        return result;
    }
    @Test void metadataOnlyLookupIsSignedStrictAndNeverCreatesAnActorFromBody() throws Exception {
        try(var f=new V2WorkflowFixture()) {
            when(store.lookup(11,22,3)).thenReturn(new WorkflowExecutionStore.Lookup("{\"sealed\":true}",HASH,"running",1800000000));var mvc=mvc(f);
            var good=lookup(mvc,f,"metadata","{\"instanceId\":11,\"attemptId\":22,\"fence\":3}",null,200);FixedJson.fields(FixedJson.parse(good.getResponse().getContentAsByteArray(),65536),"canonicalManifest","manifestSha256","status","leaseExpiresAt");
            for(String body:List.of("{}","{\"instanceId\":11,\"attemptId\":22,\"fence\":0}","{\"instanceId\":\"11\",\"attemptId\":22,\"fence\":3}","{\"instanceId\":11,\"attemptId\":22,\"fence\":3,\"actor\":\"admin\"}","{\"instanceId\":11,\"attemptId\":22,\"fence\":3,\"fence\":1}"))lookup(mvc,f,"metadata",body,null,400);
            lookup(mvc,f,"system","{\"instanceId\":11,\"attemptId\":22,\"fence\":3}",null,403);lookup(mvc,f,"metadata","{\"instanceId\":11,\"attemptId\":22,\"fence\":3}",f.token(),403);
            assertTrue(f.requests.isEmpty());verify(store,times(1)).lookup(11,22,3);
        }
    }
    @Test void staleSourceAndDatabaseFailureRemainSignedConflictOrUnavailable() throws Exception {
        try(var f=new V2WorkflowFixture()) {
            var mvc=mvc(f);when(store.lookup(11,22,3)).thenThrow(WorkflowExecutionStore.conflict());lookup(mvc,f,"metadata","{\"instanceId\":11,\"attemptId\":22,\"fence\":3}",null,409);
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("private SQL")).when(store).lookup(11,22,3);
            assertFalse(lookup(mvc,f,"metadata","{\"instanceId\":11,\"attemptId\":22,\"fence\":3}",null,503).getResponse().getContentAsString().contains("private"));
        }
    }
}
