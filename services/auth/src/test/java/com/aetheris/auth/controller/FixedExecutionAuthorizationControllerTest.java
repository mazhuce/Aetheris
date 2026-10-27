package com.aetheris.auth.controller;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.entity.*;
import com.aetheris.auth.fixedexecution.*;
import com.aetheris.auth.mapper.*;
import com.aetheris.auth.security.*;
import com.aetheris.auth.service.*;
import com.aetheris.auth.config.AuthV2SecurityConfiguration;
import com.aetheris.auth.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.aetheris.security.servlet.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real Ed25519/filter/MVC and real session tuple checks; DB/account/permission dependencies are local doubles. */
class FixedExecutionAuthorizationControllerTest {
    static final String PATH=FixedExecutionAuthorizationController.PATH,OP=FixedExecutionAuthorizationController.OPERATION;
    static final ObjectMapper JSON=new ObjectMapper();static final String HASH="a".repeat(64);
    final V2ProofFixture proof=new V2ProofFixture();final AuthUserService users=mock(AuthUserService.class);
    final PermissionDecisionService decisions=mock(PermissionDecisionService.class);final PermissionAuditClient audit=mock(PermissionAuditClient.class);
    final Map<String,AuthUser> accounts=new HashMap<>();final Set<String> used=ConcurrentHashMap.newKeySet();
    FixedExecutionAuthorizationService service;MockMvc mvc;
    @BeforeEach void setup() {
        account(2,"operator");account(3,"approver");account(4,"parent-approver");
        when(users.findByUsername(anyString())).thenAnswer(i->Optional.ofNullable(accounts.get(i.getArgument(0))));
        when(decisions.check(anyString(),anyString(),anyLong(),anyString(),eq(Map.of()))).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        service=new FixedExecutionAuthorizationService(new AuthSessionValidator(users),decisions,audit);build(service);
    }
    void account(long id,String name) {var user=new AuthUser();user.setId(id);user.setUsername(name);user.setRole("viewer");user.setStatus("active");user.setAccessVersion(0L);accounts.put(name,user);}
    void build(FixedExecutionAuthorizationService authorization) {
        var sessions=new AuthSessionValidator(users);
        mvc=MockMvcBuilders.standaloneSetup(new FixedExecutionAuthorizationController(authorization))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(proof.workload((caller,nonce,expiry)->used.add(caller+":"+nonce),sessions),proof.direct(sessions)).build();
    }
    ObjectNode input(String origin) {
        var n=JSON.createObjectNode();n.put("schemaVersion",1);var b=n.putObject("binding");
        b.put("origin",origin).put("operation",switch(origin){case "workflow"->"metadata.fixed-sql.submit";case "ingestion"->"metadata.fixed-transfer.submit";default->"metadata.fixed-quality-query.submit";})
                .put("resourceId",7).put("runId",11).put("attemptId",origin.equals("workflow")?13:0).put("fence",1).put("executionRevision",2).put("manifestSha256",HASH);
        n.putObject("actor").put("kind","manual").put("uid",2).put("username","operator").put("accessVersion",0).put("requestId","request-123");
        n.set("approval",approval(3,"approver",2));
        var targets=n.putObject("targets");targets.put("sourceDatasourceId",5);if(origin.equals("quality"))targets.putNull("targetDatasourceId");else targets.put("targetDatasourceId",5);
        n.putNull("parent");return n;
    }
    ObjectNode approval(long uid,String name,int revision) {return JSON.createObjectNode().put("uid",uid).put("username",name).put("accessVersion",0).put("revision",revision).put("definitionSha256",HASH);}
    ObjectNode child() {
        var n=input("quality");((ObjectNode)n.get("actor")).put("kind","workflow-child");
        n.putObject("parent").put("resourceId",9).put("runId",20).put("attemptId",21).put("fence",2).put("manifestSha256",HASH).set("approval",approval(4,"parent-approver",3));return n;
    }
    MvcResult call(String body,int status) throws Exception {return call("metadata",OP,body,null,status);}
    MvcResult call(String caller,String operation,String body,String user,int expectedStatus) throws Exception {
        byte[] raw=body.getBytes(StandardCharsets.UTF_8);
        String token=proof.request(proof.signer(caller,caller,KeyPurpose.WORKLOAD),caller,"POST",PATH,operation,raw,user);
        var builder=post(PATH).contentType("application/json").content(raw).header("X-Aetheris-Workload-Proof",token).header("X-User-Name","admin");
        if(user!=null)builder.header("X-Aetheris-User-Proof",user);
        var result=mvc.perform(builder).andReturn();assertEquals(expectedStatus,result.getResponse().getStatus());
        if(expectedStatus==200 || expectedStatus==400 || expectedStatus==503) {
            var request=proof.verifier.verifyRequest(token,new ExpectedRequest("auth","POST",PATH,BodyHash.sha256(raw),operation,BodyHash.userProof(user)));
            proof.verifier.verifyResponse(result.getResponse().getHeader("X-Aetheris-Response-Proof"),new ExpectedResponse("auth",caller,request.proof().nonce(),expectedStatus,BodyHash.sha256(result.getResponse().getContentAsByteArray())));
        }
        return result;
    }
    JsonNode result(MvcResult result) throws Exception{return JSON.readTree(result.getResponse().getContentAsByteArray());}
    @Test void ordinaryActorExecuteAndApproverUnderlyingRightsHaveExactBoundResponse() throws Exception {
        var input=input("workflow");var out=result(call(input.toString(),200));
        assertEquals(Set.of("binding","allowed","reason","checks"),fields(out));assertEquals(input.get("binding"),out.get("binding"));
        assertTrue(out.get("allowed").booleanValue());assertTrue(out.get("reason").isNull());assertEquals(3,out.get("checks").size());
        for(var check:out.get("checks"))assertEquals(Set.of("subject","resourceType","resourceId","action","allowed"),fields(check));
        var ordered=inOrder(decisions);ordered.verify(decisions).check("operator","workflow_task",7L,"execute",Map.of());
        ordered.verify(decisions).check("approver","datasource",5L,"query",Map.of());ordered.verify(decisions).check("approver","datasource",5L,"write",Map.of());
        verifyNoMoreInteractions(decisions);verifyNoInteractions(audit);
    }
    static Set<String> fields(JsonNode n){Set<String> f=new HashSet<>();n.fieldNames().forEachRemaining(f::add);return f;}
    @Test void transferAndQualityUseOnlyTheirOwnFixedChecks() throws Exception {
        var transfer=input("ingestion");((ObjectNode)transfer.get("targets")).put("targetDatasourceId",8);
        assertTrue(result(call(transfer.toString(),200)).get("allowed").booleanValue());
        assertEquals(2,result(call(input("quality").toString(),200)).get("checks").size());
        verify(decisions).check("operator","ingestion_task",7L,"execute",Map.of());verify(decisions).check("approver","datasource",8L,"write",Map.of());
        verify(decisions).check("operator","quality_rule",7L,"execute",Map.of());verify(decisions,never()).check(eq("operator"),eq("datasource"),anyLong(),anyString(),anyMap());
    }
    @Test void workflowChildChecksActorParentAndParentApproverChildBinding() throws Exception {
        var out=result(call(child().toString(),200));assertTrue(out.get("allowed").booleanValue());assertEquals(4,out.get("checks").size());
        verify(decisions).check("operator","workflow_task",9L,"execute",Map.of());verify(decisions).check("parent-approver","quality_rule",7L,"execute",Map.of());
        when(decisions.check("parent-approver","quality_rule",7L,"execute",Map.of())).thenReturn(PermissionDecisionService.Decision.denied("private detail"));
        assertEquals("PERMISSION_DENIED",result(call(child().toString(),200)).get("reason").textValue());
    }
    @Test void identityReplacementDuringPermissionLookupCannotAuthorizeTheOldInvocation() throws Exception {
        for(String part:List.of("actor","approval","parent"))for(String mutation:List.of("uid","av","disabled")) {
            account(2,"operator");account(3,"approver");account(4,"parent-approver");
            String name=part.equals("actor")?"operator":part.equals("approval")?"approver":"parent-approver";
            String type=part.equals("parent")?"quality_rule":"workflow_task";
            doAnswer(invocation->{var value=accounts.get(name);
                if(mutation.equals("uid"))value.setId(99L);
                if(mutation.equals("av"))value.setAccessVersion(1L);
                if(mutation.equals("disabled"))value.setStatus("disabled");
                return PermissionDecisionService.Decision.ALLOWED;
            }).when(decisions).check("operator",type,7L,"execute",Map.of());
            var out=result(call((part.equals("parent")?child():input("workflow")).toString(),200));
            assertFalse(out.path("allowed").booleanValue(),part+":"+mutation);
            assertEquals("SUBJECT_INACTIVE",out.path("reason").asText());
        }
    }
    @Test void actorApprovalAndParentTupleMismatchDisabledMissingOrAvChangeAreNotPermissions() throws Exception {
        for(String part:List.of("actor","approval","parent")) {
            var n=part.equals("parent")?child():input("workflow");ObjectNode subject=(ObjectNode)(part.equals("parent")?n.path("parent").path("approval"):n.get(part));
            for(String mutation:List.of("uid","av","missing","disabled")) {
                var changed=n.deepCopy();ObjectNode value=(ObjectNode)(part.equals("parent")?changed.path("parent").path("approval"):changed.get(part));
                if(mutation.equals("uid"))value.put("uid",99);if(mutation.equals("av"))value.put("accessVersion",1);
                if(mutation.equals("missing"))value.put("username","absent");
                String name=subject.get("username").textValue();if(mutation.equals("disabled"))accounts.get(name).setStatus("disabled");
                var out=result(call(changed.toString(),200));assertFalse(out.get("allowed").booleanValue());assertEquals("SUBJECT_INACTIVE",out.get("reason").textValue());assertTrue(out.get("checks").isEmpty());
                accounts.get(name).setStatus("active");
            }
        }
        verifyNoInteractions(decisions);
    }
    @Test void anyDeniedDependencyDeniesWholeDecisionWithoutExposingReasonOrProof() throws Exception {
        when(decisions.check("approver","datasource",5L,"write",Map.of())).thenReturn(PermissionDecisionService.Decision.denied("sensitive SQL and proof detail"));
        var response=call(input("workflow").toString(),200);var out=result(response);assertFalse(out.get("allowed").booleanValue());assertEquals("PERMISSION_DENIED",out.get("reason").textValue());
        assertFalse(response.getResponse().getContentAsString().contains("sensitive"));
        var event=org.mockito.ArgumentCaptor.forClass(com.aetheris.auth.audit.PermissionEventRequest.class);verify(audit).recordAsync(event.capture(),any());
        assertEquals("PERMISSION_DENIED",event.getValue().getReason());assertEquals("request-123",event.getValue().getRequestId());
    }
    @Test void databaseAndTupleLookupFailuresAreSigned503AndDoNotReturnPartialAllow() throws Exception {
        when(decisions.check("approver","datasource",5L,"write",Map.of())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private-db-detail"));
        var response=call(input("workflow").toString(),503);assertEquals("{\"reason\":\"AUTH_UNAVAILABLE\"}",response.getResponse().getContentAsString());
        reset(decisions);when(users.findByUsername("operator")).thenThrow(new IllegalStateException("private-lookup"));
        assertEquals("AUTH_UNAVAILABLE",result(call(input("workflow").toString(),503)).get("reason").textValue());
    }
    @Test void unknownDuplicateTrailingWrongTypesMissingNullAndOverflowNeverReachAccounts() throws Exception {
        String good=input("workflow").toString();var bad=new ArrayList<String>();bad.add("null");bad.add("[]");bad.add(good+"{}");
        bad.add(good.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1"));
        for(String replacement:List.of("\"1\"","1.0","true","null","9223372036854775808"))bad.add(good.replace("\"uid\":2","\"uid\":"+replacement));
        for(String replacement:List.of("true","8","null"))bad.add(good.replace("\"username\":\"operator\"","\"username\":"+replacement));
        // Preserve the invalid UTF-16 unit on the JSON wire; encoding a literal lone surrogate would replace it with '?'.
        bad.add(good.replace("\"username\":\"operator\"","\"username\":\"a\\ud800\""));
        for(String replacement:List.of("null","\"2\"","2.0","true","9223372036854775808"))bad.add(good.replace("\"executionRevision\":2","\"executionRevision\":"+replacement));
        for(String field:List.of("binding","actor","approval","targets","parent","schemaVersion")){var n=input("workflow");n.remove(field);bad.add(n.toString());}
        for(String name:List.of("username","resourceType","action","ctx","caller")){var n=input("workflow");n.put(name,"admin");bad.add(n.toString());}
        var n=input("workflow");((ObjectNode)n.get("actor")).put("role","admin");bad.add(n.toString());
        n=input("workflow");((ObjectNode)n.get("approval")).put("ctx","*");bad.add(n.toString());
        for(String body:bad)call(body,400);verifyNoInteractions(users,decisions,audit);
    }
    @Test void operationTargetsRevisionParentAndDomainValuesAreValidated() throws Exception {
        List<ObjectNode> bad=new ArrayList<>();
        for(String origin:List.of("workflow","ingestion","quality")) {
            var n=input(origin);((ObjectNode)n.get("binding")).put("operation","metadata.user.sql-query");bad.add(n);
            n=input(origin);((ObjectNode)n.get("approval")).put("revision",3);bad.add(n);
            n=input(origin);((ObjectNode)n.get("binding")).put("attemptId",origin.equals("workflow")?0:1);bad.add(n);
        }
        var n=input("workflow");((ObjectNode)n.get("targets")).put("targetDatasourceId",8);bad.add(n);
        n=input("ingestion");((ObjectNode)n.get("targets")).putNull("targetDatasourceId");bad.add(n);
        n=input("quality");((ObjectNode)n.get("targets")).put("targetDatasourceId",5);bad.add(n);
        for(String field:List.of("resourceId","runId","fence","executionRevision")){n=input("workflow");((ObjectNode)n.get("binding")).put(field,0);bad.add(n);}
        for(String name:List.of("old name","a\u200bb","a|b","a".repeat(65))){n=input("workflow");((ObjectNode)n.get("actor")).put("username",name);bad.add(n);}
        for(String value:List.of("","space id","x".repeat(129))){n=input("workflow");((ObjectNode)n.get("actor")).put("requestId",value);bad.add(n);}
        n=input("workflow");((ObjectNode)n.get("binding")).put("manifestSha256","A".repeat(64));bad.add(n);
        n=input("workflow");((ObjectNode)n.get("actor")).put("kind","retry");bad.add(n);
        n=input("quality");((ObjectNode)n.get("actor")).put("kind","workflow-child");bad.add(n);
        n=child();((ObjectNode)n.get("actor")).put("kind","manual");bad.add(n);
        n=input("workflow");n.set("parent",child().get("parent"));((ObjectNode)n.get("actor")).put("kind","workflow-child");bad.add(n);
        n=child();((ObjectNode)n.get("parent")).put("attemptId",0);bad.add(n);
        for(int index=0;index<bad.size();index++) {
            try {call(bad.get(index).toString(),400);}catch(AssertionError failure){throw new AssertionError("Invalid contract mutation index "+index,failure);}
        }
        verifyNoInteractions(users,decisions,audit);
    }
    @Test void exactByteLimitAndNestingAreEnforcedWithoutSkippingTests() throws Exception {
        String good=input("workflow").toString();String boundary=good+" ".repeat(65536-good.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(result(call(boundary,200)).get("allowed").booleanValue());
        call(boundary+" ",400);
        call("{\"schemaVersion\":"+"[".repeat(13)+"1"+"]".repeat(13)+"}",400);
        verify(decisions,times(3)).check(anyString(),anyString(),anyLong(),anyString(),eq(Map.of()));
    }
    @Test void metadataOnlyNoUserNoLegacyAndMissingFilterAreFailClosed() throws Exception {
        String raw=input("workflow").toString();
        for(String caller:List.of("system","serving","gateway","outsider"))call(caller,OP,raw,null,403);
        call("metadata",OP,raw,proof.user("metadata",2,"operator",0),403);
        call("metadata","auth.permission.check",raw,null,401);
        assertEquals(401,mvc.perform(post(PATH).contentType("application/json").content(raw).header("X-Internal-Token","old")).andReturn().getResponse().getStatus());
        var naked=MockMvcBuilders.standaloneSetup(new FixedExecutionAuthorizationController(service)).build();
        assertEquals(401,naked.perform(post(PATH).contentType("application/json").content(raw).header("X-User-Name","admin")).andReturn().getResponse().getStatus());
        verifyNoInteractions(users,decisions,audit);
    }
    @Test void replayCanNeverDecideAgainAndOriginalRoutesDoNotExpand() throws Exception {
        String raw=input("workflow").toString();byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
        String token=proof.request(proof.signer("metadata","metadata",KeyPurpose.WORKLOAD),"metadata","POST",PATH,OP,bytes,null);
        for(int expected:List.of(200,409))assertEquals(expected,mvc.perform(post(PATH).contentType("application/json").content(bytes).header("X-Aetheris-Workload-Proof",token)).andReturn().getResponse().getStatus());
        verify(decisions,times(3)).check(anyString(),anyString(),anyLong(),anyString(),eq(Map.of()));
        var routes=AuthV2SecurityConfiguration.workloadRoutes();assertTrue(routes.resolve("GET",PATH).isEmpty());assertTrue(routes.resolve("POST",PATH+"/child").isEmpty());
        assertEquals(RoutePolicy.UserMode.REQUIRED,routes.resolve("POST","/internal/v2/permissions/check").orElseThrow().userMode());
        assertFalse(routes.resolve("POST","/internal/v2/permissions/check").orElseThrow().allowedCallers().contains("gateway"));
        assertFalse(routes.resolve("POST","/internal/v2/permissions/may-manage").orElseThrow().allowedCallers().contains("metadata"));
        assertEquals(RoutePolicy.UserMode.FORBIDDEN,routes.resolve("POST","/internal/v2/sessions/check").orElseThrow().userMode());
    }
    @Test void realDecisionEngineAllowsOnlyGrantedDefinitionAndApproverWriteNotActorSql() throws Exception {
        var grants=mock(ResourceGrantMapper.class);var groups=mock(UserGroupMapper.class);var roles=mock(UserRoleMapper.class);
        List<ResourceGrant> approvalGrants=new ArrayList<>(List.of(grant("datasource",5,"query"),grant("datasource",5,"write")));
        when(grants.selectEnabledFor("user",2L)).thenReturn(List.of(grant("workflow_task",7,"execute")));
        when(grants.selectEnabledFor("user",3L)).thenReturn(approvalGrants);
        when(groups.selectGroupIdsOfUser(anyLong())).thenReturn(List.of());when(roles.selectRoleIdsOfUser(anyLong())).thenReturn(List.of());
        var engine=new PermissionDecisionService(users,grants,groups,roles,mock(RbacRoleMapper.class));
        build(new FixedExecutionAuthorizationService(new AuthSessionValidator(users),engine,audit));
        assertTrue(result(call(input("workflow").toString(),200)).get("allowed").booleanValue());
        assertFalse(engine.check("operator","datasource",5L,"query",Map.of()).allowed());assertFalse(engine.check("operator","datasource",5L,"write",Map.of()).allowed());
        approvalGrants.removeLast();assertFalse(result(call(input("workflow").toString(),200)).get("allowed").booleanValue());
        approvalGrants.add(grant("datasource",5,"write"));var other=input("workflow");((ObjectNode)other.get("binding")).put("resourceId",8);
        assertFalse(result(call(other.toString(),200)).get("allowed").booleanValue());
    }
    static ResourceGrant grant(String type,long id,String action){var g=new ResourceGrant();g.setResourceType(type);g.setResourceId(id);g.setActions(List.of(action));return g;}
}
