package com.aetheris.auth.controller;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.security.*;
import com.aetheris.auth.service.*;
import com.aetheris.auth.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.NonceStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InternalV2ControllerTest {
    final V2ProofFixture proof=new V2ProofFixture();
    final AuthUserService users=mock(AuthUserService.class);
    final PermissionDecisionService decisions=mock(PermissionDecisionService.class);
    final PermissionVersionService versions=mock(PermissionVersionService.class);
    final PermissionAuditClient audit=mock(PermissionAuditClient.class);
    final AuthSessionValidator sessions=new AuthSessionValidator(users);
    final Set<String> nonces=ConcurrentHashMap.newKeySet();
    NonceStore nonceStore=(c,n,e)->nonces.add(c+":"+n);
    jakarta.validation.ValidatorFactory validators;
    MockMvc mvc;
    final java.util.concurrent.atomic.AtomicReference<Exception> chainFailure=new java.util.concurrent.atomic.AtomicReference<>();
    @BeforeEach void setup() {
        validators=Validation.buildDefaultValidatorFactory();
        build();
        AuthUser alice=new AuthUser();alice.setId(42L);alice.setUsername("alice");alice.setStatus("active");alice.setAccessVersion(3L);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(decisions.check(eq("alice"),anyString(),any(),anyString(),anyMap())).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        when(decisions.mayManage("alice")).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        when(decisions.mayMutate("alice","metadata")).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        when(decisions.visibility("alice","table","query")).thenReturn(new PermissionDecisionService.Visibility(PermissionDecisionService.Mode.IDS,List.of(7L)));
        when(decisions.allowedIds(eq("alice"),eq("table"),eq("query"),anyMap())).thenReturn(Set.of(7L));
        when(versions.current()).thenReturn(4L);
    }
    void build() {
        var json=new ObjectMapper().findAndRegisterModules().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mvc=MockMvcBuilders.standaloneSetup(new InternalV2Controller(sessions,decisions,versions,audit,validators.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(json))
                .addFilters(proof.workload(nonceStore,sessions),(jakarta.servlet.Filter)(request,response,chain)->{
                    try{chain.doFilter(request,response);}catch(java.io.IOException | jakarta.servlet.ServletException | RuntimeException failure){chainFailure.set(failure);throw failure;}
                },proof.direct(sessions)).build();
    }
    @AfterEach void close(){validators.close();}
    @Test void badDtoWithoutProofFilterIsHandledByControllerAdvice() throws Exception {
        var direct=MockMvcBuilders.standaloneSetup(new InternalV2Controller(sessions,decisions,versions,audit,validators.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        direct.perform(post("/internal/v2/permissions/may-manage").contentType("application/json").content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest());
    }
    String user(){return proof.user("system",42,"alice",3);}
    String resourceUser(){return proof.user("metadata",42,"alice",3);}
    @Test void globalAuditReadUsesTheOriginalUserAndCannotAuthorizeAdjacentActions() throws Exception {
        String raw="{\"resourceType\":\"query_audit\",\"resourceId\":null,\"action\":\"view\"}";
        mvc.perform(signed("permissions/check","auth.permission.check",raw,resourceUser()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true))
                .andExpect(header().exists("X-Aetheris-Response-Proof"));
        verify(decisions).check("alice","query_audit",null,"view",Map.of());
        when(decisions.check("alice","query_audit",null,"view",Map.of()))
                .thenReturn(PermissionDecisionService.Decision.denied("Revoked"));
        mvc.perform(signed("permissions/check","auth.permission.check",raw,resourceUser()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
        mvc.perform(signed("permissions/check","auth.permission.check",raw.replace("\"view\"","\"delete\""),resourceUser()))
                .andExpect(status().isForbidden());
        verify(decisions,never()).check("alice","query_audit",null,"delete",Map.of());
    }
    MockHttpServletRequestBuilder signed(String suffix,String operation,String body,String user) {
        String path="/internal/v2/"+suffix;byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        var request=post(path).contentType("application/json").content(bytes)
                .header("X-Aetheris-Workload-Proof",proof.request(path,operation,bytes,user));
        if(user!=null)request.header("X-Aetheris-User-Proof",user);
        return request;
    }
    @Test void realProofAndRawResponseSignatureForAllowAndDenied() throws Exception {
        String path="/internal/v2/permissions/check", user=resourceUser();byte[] bytes="{\"resourceType\":\"table\",\"resourceId\":7,\"action\":\"query\"}".getBytes(StandardCharsets.UTF_8);
        String token=proof.request(path,"auth.permission.check",bytes,user);
        var input=proof.verifier.verifyRequest(token,new ExpectedRequest("auth","POST",path,BodyHash.sha256(bytes),"auth.permission.check",BodyHash.userProof(user)));
        var result=mvc.perform(post(path).contentType("application/json").content(bytes).header("X-Aetheris-Workload-Proof",token)
                .header("X-Aetheris-User-Proof",user).header("X-User-Name","admin")).andExpect(status().isOk())
                .andExpect(content().json("{\"allowed\":true,\"reason\":null}",true)).andReturn();
        assertNotNull(proof.verifier.verifyResponse(result.getResponse().getHeader("X-Aetheris-Response-Proof"),new ExpectedResponse("auth","metadata",input.proof().nonce(),200,BodyHash.sha256(result.getResponse().getContentAsByteArray()))));
        when(decisions.check(eq("alice"),anyString(),any(),anyString(),anyMap())).thenReturn(PermissionDecisionService.Decision.denied("revoked"));
        mvc.perform(signed("permissions/check","auth.permission.check",new String(bytes,StandardCharsets.UTF_8),user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false)).andExpect(header().exists("X-Aetheris-Response-Proof"));
        verify(audit).recordAsync(any(),any());
    }
    MockHttpServletRequestBuilder ingestionCheck(String body,String user) {
        String path="/internal/v2/permissions/check";byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        String token=proof.request(proof.signer("ingestion","ingestion",KeyPurpose.WORKLOAD),"ingestion","POST",path,"auth.permission.check",bytes,user);
        return post(path).contentType("application/json").content(bytes).header("X-Aetheris-Workload-Proof",token).header("X-Aetheris-User-Proof",user);
    }
    @Test void ingestionApprovalChecksUseTheOriginalUserForOnlyDatasourceQueryAndWrite() throws Exception {
        String user=proof.user("ingestion",42,"alice",3);
        for(String action:List.of("query","write")) {
            String body="{\"resourceType\":\"datasource\",\"resourceId\":7,\"action\":\""+action+"\"}";
            mvc.perform(ingestionCheck(body,user).header("X-User-Name","admin"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true)).andExpect(header().exists("X-Aetheris-Response-Proof"));
            verify(decisions).check("alice","datasource",7L,action,Map.of());
        }
        when(decisions.check("alice","datasource",8L,"write",Map.of())).thenReturn(PermissionDecisionService.Decision.denied("revoked"));
        mvc.perform(ingestionCheck("{\"resourceType\":\"datasource\",\"resourceId\":8,\"action\":\"write\"}",user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
    }
    @Test void ingestionApprovalCannotQueryAdjacentCapabilitiesOrRelayAnotherAudience() throws Exception {
        String user=proof.user("ingestion",42,"alice",3);
        for(String action:List.of("view","update","delete","execute","create"))
            mvc.perform(ingestionCheck("{\"resourceType\":\"datasource\",\"resourceId\":7,\"action\":\""+action+"\"}",user)).andExpect(status().isForbidden());
        for(String type:List.of("table","quality_rule","workflow_task"))
            mvc.perform(ingestionCheck("{\"resourceType\":\""+type+"\",\"resourceId\":7,\"action\":\"query\"}",user)).andExpect(status().isForbidden());
        mvc.perform(ingestionCheck("{\"resourceType\":\"datasource\",\"resourceId\":7,\"action\":\"query\"}",proof.user("serving",42,"alice",3))).andExpect(status().isUnauthorized());
        verifyNoInteractions(decisions);
    }
    @Test void qualityApprovalCanCheckOnlyItsOriginalUsersDatasourceReadCapability() throws Exception {
        String path="/internal/v2/permissions/check",user=proof.user("quality",42,"alice",3);
        var signer=proof.signer("quality","quality",KeyPurpose.WORKLOAD);
        for(String action:List.of("query","write","update","delete","execute")) {
            byte[] raw=("{\"resourceType\":\"datasource\",\"resourceId\":7,\"action\":\""+action+"\"}").getBytes(StandardCharsets.UTF_8);
            String token=proof.request(signer,"quality","POST",path,"auth.permission.check",raw,user);
            mvc.perform(post(path).contentType("application/json").content(raw).header("X-Aetheris-Workload-Proof",token)
                    .header("X-Aetheris-User-Proof",user).header("X-User-Name","admin"))
                    .andExpect(status().is(action.equals("query")?200:403));
        }
        verify(decisions).check("alice","datasource",7L,"query",Map.of());
        verifyNoMoreInteractions(decisions);
    }
    @Test void workflowSqlApprovalCanCheckOnlyItsOriginalUsersDatasourceQueryAndWrite() throws Exception {
        String path="/internal/v2/permissions/check",user=proof.user("workflow",42,"alice",3);
        var signer=proof.signer("workflow","workflow",KeyPurpose.WORKLOAD);
        for(String action:List.of("query","write","view","update","delete","execute")) {
            byte[] raw=("{\"resourceType\":\"datasource\",\"resourceId\":7,\"action\":\""+action+"\"}").getBytes(StandardCharsets.UTF_8);
            String token=proof.request(signer,"workflow","POST",path,"auth.permission.check",raw,user);
            mvc.perform(post(path).contentType("application/json").content(raw).header("X-Aetheris-Workload-Proof",token)
                    .header("X-Aetheris-User-Proof",user).header("X-User-Name","admin"))
                    .andExpect(status().is(action.equals("query")||action.equals("write")?200:403));
        }
        verify(decisions).check("alice","datasource",7L,"query",Map.of());
        verify(decisions).check("alice","datasource",7L,"write",Map.of());verifyNoMoreInteractions(decisions);
    }
    @Test void metadataApiAssetManagementUsesOnlyTheOriginalUserAndFixedLifecycleActions() throws Exception {
        String user=proof.user("metadata",42,"alice",3),path="/internal/v2/permissions/check";
        for(String action:List.of("view","create","update","delete","publish","deprecate","execute","query","write","grant")) {
            byte[] raw=("{\"resourceType\":\"data_service\",\"resourceId\":11,\"action\":\""+action+"\"}").getBytes(StandardCharsets.UTF_8);
            String signed=proof.request(path,"auth.permission.check",raw,user);
            mvc.perform(post(path).contentType("application/json").content(raw).header("X-Aetheris-Workload-Proof",signed)
                    .header("X-Aetheris-User-Proof",user).header("X-User-Name","admin"))
                    .andExpect(status().is(Set.of("view","create","update","delete","publish","deprecate").contains(action)?200:403));
        }
        for(String action:List.of("view","create","update","delete","publish","deprecate"))verify(decisions).check("alice","data_service",11L,action,Map.of());
        verifyNoMoreInteractions(decisions);
    }
    @Test void everyFixedPostEndpointUsesOnlyVerifiedSubject() throws Exception {
        mvc.perform(signed("sessions/check","auth.session.check","{\"uid\":42,\"username\":\"alice\",\"accessVersion\":3}",null)).andExpect(jsonPath("$.active").value(true));
        mvc.perform(signed("permissions/visibility","auth.permission.visibility","{\"resourceType\":\"table\",\"action\":\"query\"}",resourceUser())).andExpect(jsonPath("$.ids[0]").value(7));
        mvc.perform(signed("permissions/check-batch","auth.permission.batch","{\"resourceType\":\"table\",\"action\":\"query\",\"items\":[{\"resourceId\":7}]}",resourceUser())).andExpect(jsonPath("$.allowedIds[0]").value(7));
        mvc.perform(signed("permissions/may-manage","auth.permission.manage","{}",user())).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(signed("permissions/may-mutate","auth.permission.mutate","{\"service\":\"metadata\"}",resourceUser())).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(signed("permissions/version","auth.permission.version","{}",null)).andExpect(jsonPath("$.version").value(4));
        verify(decisions).mayManage("alice");verify(decisions).mayMutate("alice","metadata");
    }
    @Test void bodyCannotReplaceUserAndRejectsUnknownDuplicateTrailingOrNestedSubjectFields() throws Exception {
        for(String body:List.of("{\"username\":\"admin\"}","{\"uid\":1}","{\"role\":\"admin\"}","{\"x\":1}","{} {}","null"))
            mvc.perform(signed("permissions/may-manage","auth.permission.manage",body,user()))
                    .andExpect(r->assertEquals(400,r.getResponse().getStatus(),"badDTO="+body+", chainFailure="+failureType()))
                    .andExpect(header().exists("X-Aetheris-Response-Proof"));
        for(String body:List.of("{\"resourceType\":\"table\",\"action\":\"query\",\"action\":\"write\"}","{\"resourceType\":\"table\"}","{\"resourceType\":4,\"action\":\"query\"}"))
            mvc.perform(signed("permissions/check","auth.permission.check",body,resourceUser())).andExpect(r -> assertEquals(400,r.getResponse().getStatus(),"body="+body+", response="+r.getResponse().getContentAsString()));
        mvc.perform(signed("permissions/check-batch","auth.permission.batch","{\"resourceType\":\"table\",\"action\":\"query\",\"items\":[{\"resourceId\":7,\"username\":\"admin\"}]}",resourceUser())).andExpect(status().isBadRequest());
        verifyNoInteractions(decisions);
    }
    String failureType(){
        Throwable failure=chainFailure.get();StringBuilder result=new StringBuilder();
        while(failure!=null){result.append(failure.getClass().getName()).append(": ").append(failure.getMessage()).append("; ");for(var frame:java.util.Arrays.stream(failure.getStackTrace()).limit(8).toList()) result.append(frame.toString()).append("; ");failure=failure.getCause();}
        return result.toString();
    }
    @Test void callerMethodOperationQueryAndMissingProofAreFailClosed() throws Exception {
        mvc.perform(post("/internal/v2/permissions/version").header("X-Internal-Token","legacy").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v2/permissions/version")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/permissions/check")).andExpect(status().isBadRequest());
        mvc.perform(signed("permissions/version","auth.permission.check","{}",null)).andExpect(status().isUnauthorized());
        mvc.perform(signed("permissions/version","auth.permission.version","{}",null).queryParam("x","1")).andExpect(status().isBadRequest());
        String path="/internal/v2/permissions/version";byte[] body="{}".getBytes(StandardCharsets.UTF_8);
        String token=proof.request(proof.signer("outsider","outsider",KeyPurpose.WORKLOAD),"outsider","POST",path,"auth.permission.version",body,null);
        mvc.perform(post(path).contentType("application/json").content(body).header("X-Aetheris-Workload-Proof",token)).andExpect(status().isForbidden());
        verifyNoInteractions(decisions,versions);
    }
    @Test void firstDeliveryCannotSwapAnotherValidUserAndNoUserRoutesForbidUser() throws Exception {
        String alice=user(), bob=proof.user("system",43,"bob",3);String path="/internal/v2/permissions/may-manage";
        String token=proof.request(path,"auth.permission.manage","{}".getBytes(StandardCharsets.UTF_8),alice);
        mvc.perform(post(path).contentType("application/json").content("{}").header("X-Aetheris-Workload-Proof",token).header("X-Aetheris-User-Proof",bob)).andExpect(status().isUnauthorized());
        mvc.perform(signed("permissions/version","auth.permission.version","{}",alice)).andExpect(status().isForbidden());
        mvc.perform(signed("permissions/may-manage","auth.permission.manage","{}",null)).andExpect(status().isUnauthorized());
        verifyNoInteractions(decisions,versions);
    }
    @Test void metadataCanRelayOnlyFixedOriginalAudiencesAndStillUsesTheActualUser() throws Exception {
        String body="{\"resourceType\":\"table\",\"resourceId\":7,\"action\":\"query\"}";
        for(String audience:List.of("metadata","serving","ai-agent"))
            mvc.perform(signed("permissions/check","auth.permission.check",body,proof.user(audience,42,"alice",3)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(signed("permissions/check","auth.permission.check",body,proof.user("workflow",42,"alice",3)))
                .andExpect(status().isUnauthorized());
        verify(decisions,times(3)).check("alice","table",7L,"query",Map.of());
    }
    @Test void verifiedCallerCannotAskForAnotherServiceResourceOrWriteUnderlyingServingData() throws Exception {
        for(var item:List.of(Map.entry("workflow_task","execute"),Map.entry("data_service","execute"))) {
            String body="{\"resourceType\":\""+item.getKey()+"\",\"resourceId\":7,\"action\":\""+item.getValue()+"\"}";
            mvc.perform(signed("permissions/check","auth.permission.check",body,resourceUser()))
                    .andExpect(status().isForbidden()).andExpect(header().exists("X-Aetheris-Response-Proof"));
        }
        String path="/internal/v2/permissions/check";String user=proof.user("serving",42,"alice",3);
        byte[] bytes="{\"resourceType\":\"datasource\",\"resourceId\":7,\"action\":\"write\"}".getBytes(StandardCharsets.UTF_8);
        String token=proof.request(proof.signer("serving","serving",KeyPurpose.WORKLOAD),"serving","POST",path,"auth.permission.check",bytes,user);
        mvc.perform(post(path).contentType("application/json").content(bytes).header("X-Aetheris-Workload-Proof",token).header("X-Aetheris-User-Proof",user))
                .andExpect(status().isForbidden());
        mvc.perform(signed("permissions/may-mutate","auth.permission.mutate","{\"service\":\"system\"}",resourceUser()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(decisions);
    }
    @Test void liveSessionMismatchOrDisabledPreventsPermissionDecisionAndSessionReportsFalse() throws Exception {
        for(var entry:List.of(Map.entry(proof.user("system",41,"alice",3),403),Map.entry(proof.user("system",42,"alice",2),403),Map.entry(proof.user("metadata",42,"alice",3),401)))
            mvc.perform(signed("permissions/may-manage","auth.permission.manage","{}",entry.getKey())).andExpect(status().is(entry.getValue()));
        users.findByUsername("alice").orElseThrow().setStatus("disabled");
        mvc.perform(signed("permissions/may-manage","auth.permission.manage","{}",user())).andExpect(status().isForbidden());
        mvc.perform(signed("sessions/check","auth.session.check","{\"uid\":42,\"username\":\"alice\",\"accessVersion\":3}",null)).andExpect(jsonPath("$.active").value(false));
        verifyNoInteractions(decisions);
    }
    @Test void replayAndNonceFailureNeverExecuteBusiness() throws Exception {
        var input=signed("permissions/version","auth.permission.version","{}",null);
        mvc.perform(input).andExpect(status().isOk());mvc.perform(input).andExpect(status().isConflict());
        verify(versions,times(1)).current();reset(versions);
        nonceStore=(c,n,e)->{throw new java.sql.SQLException("private detail");};build();
        mvc.perform(signed("permissions/version","auth.permission.version","{}",null)).andExpect(status().isServiceUnavailable()).andExpect(header().exists("X-Aetheris-Response-Proof"));
        verifyNoInteractions(versions);
    }
}

