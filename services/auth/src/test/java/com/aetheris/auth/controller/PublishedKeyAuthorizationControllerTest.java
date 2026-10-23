package com.aetheris.auth.controller;

import com.aetheris.auth.audit.*;
import com.aetheris.auth.entity.*;
import com.aetheris.auth.mapper.*;
import com.aetheris.auth.model.DataScope;
import com.aetheris.auth.publishedkey.*;
import com.aetheris.auth.security.*;
import com.aetheris.auth.service.*;
import com.aetheris.auth.web.GlobalExceptionHandler;
import com.aetheris.security.protocol.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

class PublishedKeyAuthorizationControllerTest {
    static final String PATH=PublishedKeyAuthorizationController.PATH,OP=PublishedKeyAuthorizationController.OPERATION,HASH="a".repeat(64);
    static final ObjectMapper JSON=new ObjectMapper();
    final V2ProofFixture proof=new V2ProofFixture();final AuthUserService users=mock(AuthUserService.class);
    final PermissionDecisionService decisions=mock(PermissionDecisionService.class);final PermissionAuditClient audit=mock(PermissionAuditClient.class);
    final Set<String> nonces=ConcurrentHashMap.newKeySet();final AuthUser owner=new AuthUser();MockMvc mvc;
    @BeforeEach void setup(){
        owner.setId(7L);owner.setUsername("key-owner");owner.setRole("viewer");owner.setAccessVersion(2L);owner.setStatus("active");
        when(users.findByUsername(anyString())).thenAnswer(call->"key-owner".equals(call.getArgument(0))?Optional.of(owner):Optional.empty());
        when(decisions.check(anyString(),anyString(),anyLong(),anyString(),anyMap())).thenReturn(PermissionDecisionService.Decision.ALLOWED);
        build(decisions);
    }
    void build(PermissionDecisionService engine){
        var sessions=new AuthSessionValidator(users);
        mvc=MockMvcBuilders.standaloneSetup(new PublishedKeyAuthorizationController(new PublishedKeyAuthorizationService(sessions,engine,audit)))
                .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new ByteArrayHttpMessageConverter(),new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(proof.workload((caller,nonce,expiry)->nonces.add(caller+":"+nonce),sessions),proof.direct(sessions)).build();
    }
    ObjectNode input(String type){
        var n=JSON.createObjectNode().put("schemaVersion",1);
        n.putObject("binding").put("admissionId","7feb99a1-1801-46b1-9bba-d8254406de99").put("keyId",3).put("assetId",11).put("assetVersion","v1")
                .put("admissionSha256",HASH).put("parametersSha256","b".repeat(64)).put("requestId","published-key-test");
        n.putObject("owner").put("uid",7).put("username","key-owner").put("accessVersion",2);
        n.putObject("target").put("resourceType",type).put("resourceId",13).put("domainId",5).put("domainCode","warehouse");return n;
    }
    MvcResult call(String body,int status)throws Exception{return call("metadata",body,null,status);}
    MvcResult call(String caller,String body,String user,int expected)throws Exception{
        byte[] raw=body.getBytes(StandardCharsets.UTF_8);String token=proof.request(proof.signer(caller,caller,KeyPurpose.WORKLOAD),caller,"POST",PATH,OP,raw,user);
        var request=post(PATH).contentType("application/json").content(raw).header("X-Aetheris-Workload-Proof",token).header("X-User-Role","admin");
        if(user!=null)request.header("X-Aetheris-User-Proof",user);
        var out=mvc.perform(request).andReturn();assertEquals(expected,out.getResponse().getStatus());
        if(Set.of(200,400,503).contains(expected)){
            var verified=proof.verifier.verifyRequest(token,new ExpectedRequest("auth","POST",PATH,BodyHash.sha256(raw),OP,BodyHash.userProof(user)));
            proof.verifier.verifyResponse(out.getResponse().getHeader("X-Aetheris-Response-Proof"),new ExpectedResponse("auth",caller,verified.proof().nonce(),expected,BodyHash.sha256(out.getResponse().getContentAsByteArray())));
        }return out;
    }
    JsonNode result(MvcResult out)throws Exception{return JSON.readTree(out.getResponse().getContentAsByteArray());}
    @Test void exactTwoPermissionsUseOwnerAndMetadataDerivedDomainContext()throws Exception{
        var n=input("table");var out=result(call(n.toString(),200));assertEquals(n.get("binding"),out.get("binding"));assertTrue(out.get("allowed").booleanValue());
        assertTrue(out.get("reason").isNull());assertEquals(2,out.get("checks").size());
        var order=inOrder(decisions);order.verify(decisions).check("key-owner","data_service",11L,"execute",Map.of());
        order.verify(decisions).check("key-owner","table",13L,"query",Map.of("tableId","13","domainId","5","domainCode","warehouse"));verifyNoMoreInteractions(decisions);
        verifyNoInteractions(audit);
    }
    @Test void metricAndNullableDomainHaveFixedContextAndNoCallerChosenFields()throws Exception{
        var n=input("metric");((ObjectNode)n.get("target")).putNull("domainId").putNull("domainCode");
        assertTrue(result(call(n.toString(),200)).get("allowed").booleanValue());
        verify(decisions).check("key-owner","metric",13L,"query",Map.of("metricId","13"));
    }
    @Test void eitherRevokedPermissionDeniesAndPrivateReasonIsNeverReturned()throws Exception{
        for(String denied:List.of("data_service","table")){
            reset(decisions);when(decisions.check(anyString(),anyString(),anyLong(),anyString(),anyMap())).thenAnswer(call->denied.equals(call.getArgument(1))?PermissionDecisionService.Decision.denied("private-grant-detail"):PermissionDecisionService.Decision.ALLOWED);
            var response=call(input("table").toString(),200);var out=result(response);assertFalse(out.get("allowed").booleanValue());assertEquals("PERMISSION_DENIED",out.get("reason").textValue());
            assertEquals(2,out.get("checks").size());assertFalse(response.getResponse().getContentAsString().contains("private-grant"));
        }
    }
    @Test void uidNameDisabledAndAccessVersionCannotBeReboundByMetadataBody()throws Exception{
        for(String change:List.of("uid","username","accessVersion","disabled")){
            var n=input("table");var subject=(ObjectNode)n.get("owner");
            switch(change){case "uid"->subject.put("uid",8);case "username"->subject.put("username","unknown");case "accessVersion"->subject.put("accessVersion",3);default->owner.setStatus("disabled");}
            var out=result(call(n.toString(),200));assertFalse(out.get("allowed").booleanValue());assertEquals("SUBJECT_INACTIVE",out.get("reason").textValue());assertTrue(out.get("checks").isEmpty());owner.setStatus("active");
        }verifyNoInteractions(decisions);
    }
    @Test void dependencyFailureIsSigned503AndContainsNoPartialAllow()throws Exception{
        when(decisions.check(eq("key-owner"),eq("table"),eq(13L),eq("query"),anyMap())).thenThrow(new IllegalStateException("private-database"));
        assertEquals("{\"reason\":\"AUTH_UNAVAILABLE\"}",call(input("table").toString(),503).getResponse().getContentAsString());
        when(users.findByUsername(anyString())).thenThrow(new IllegalStateException("private-user"));
        assertEquals("AUTH_UNAVAILABLE",result(call(input("table").toString(),503)).get("reason").textValue());
    }
    @Test void strictByteTypeFieldsAndNullableShapeFailBeforeAccountLookup()throws Exception{
        String good=input("table").toString();var bad=new ArrayList<String>(List.of("null","[]",good+"{}",good.replace("\"schemaVersion\":1","\"schemaVersion\":1,\"schemaVersion\":1")));
        for(String value:List.of("\"7\"","7.0","true","null","9223372036854775808"))bad.add(good.replace("\"uid\":7","\"uid\":"+value));
        for(String field:List.of("binding","owner","target","schemaVersion")){var n=input("table");n.remove(field);bad.add(n.toString());}
        for(String field:List.of("username","action","ctx","caller","sql")){var n=input("table");n.put(field,"admin");bad.add(n.toString());}
        var n=input("table");((ObjectNode)n.get("target")).put("fieldId",99);bad.add(n.toString());
        n=input("table");((ObjectNode)n.get("target")).remove("domainId");bad.add(n.toString());
        bad.add(good+" ".repeat(16385));bad.add("{\"schemaVersion\":"+"[".repeat(9)+"1"+"]".repeat(9)+"}");
        for(String raw:bad)call(raw,400);verifyNoInteractions(users,decisions,audit);
    }
    @Test void invalidSemanticsNeverSelectArbitraryResourceActionsOrActor()throws Exception{
        var invalid=new ArrayList<ObjectNode>();
        for(String type:List.of("datasource","auth_user","field","workspace")){var n=input("table");((ObjectNode)n.get("target")).put("resourceType",type);invalid.add(n);}
        for(String field:List.of("keyId","assetId")){var n=input("table");((ObjectNode)n.get("binding")).put(field,0);invalid.add(n);}
        for(String name:List.of("admin user","a|b","a\u200bb","a".repeat(65))){var n=input("table");((ObjectNode)n.get("owner")).put("username",name);invalid.add(n);}
        var n=input("table");((ObjectNode)n.get("binding")).put("assetVersion","latest");invalid.add(n);
        n=input("table");((ObjectNode)n.get("binding")).put("admissionId","arbitrary");invalid.add(n);
        n=input("table");((ObjectNode)n.get("binding")).put("requestId","header injection\r\n");invalid.add(n);
        n=input("table");((ObjectNode)n.get("binding")).put("admissionSha256","A".repeat(64));invalid.add(n);
        for(var body:invalid)call(body.toString(),400);verifyNoInteractions(users,decisions,audit);
    }
    @Test void noUserMetadataOnlyReplayAndMissingFilterStayClosed()throws Exception{
        String body=input("table").toString();for(String caller:List.of("serving","system","gateway","outsider"))call(caller,body,null,403);
        call("metadata",body,proof.user("metadata",7,"key-owner",2),403);
        assertEquals(401,mvc.perform(post(PATH).contentType("application/json").content(body).header("X-Internal-Token","old")).andReturn().getResponse().getStatus());
        var naked=MockMvcBuilders.standaloneSetup(new PublishedKeyAuthorizationController(new PublishedKeyAuthorizationService(new AuthSessionValidator(users),decisions,audit))).build();
        assertEquals(401,naked.perform(post(PATH).contentType("application/json").content(body).header("X-User-Name","admin")).andReturn().getResponse().getStatus());
        verifyNoInteractions(users,decisions,audit);
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);String token=proof.request(proof.signer("metadata","metadata",KeyPurpose.WORKLOAD),"metadata","POST",PATH,OP,bytes,null);
        for(int expected:List.of(200,409))assertEquals(expected,mvc.perform(post(PATH).contentType("application/json").content(bytes).header("X-Aetheris-Workload-Proof",token)).andReturn().getResponse().getStatus());
        verify(decisions,times(2)).check(anyString(),anyString(),anyLong(),anyString(),anyMap());
    }
    @Test void actualDecisionEngineMatchesDomainButFieldAndUnknownScopesAreNotAll()throws Exception{
        var grants=mock(ResourceGrantMapper.class);var groups=mock(UserGroupMapper.class);var roles=mock(UserRoleMapper.class);
        var asset=grant("data_service",11,"execute");var table=grant("table",13,"query");table.setDataScope(new DataScope("domain",List.of("warehouse")));
        when(grants.selectEnabledFor("user",7L)).thenReturn(List.of(asset,table));when(groups.selectGroupIdsOfUser(7L)).thenReturn(List.of());when(roles.selectRoleIdsOfUser(7L)).thenReturn(List.of());
        build(new PermissionDecisionService(users,grants,groups,roles,mock(RbacRoleMapper.class)));
        assertTrue(result(call(input("table").toString(),200)).get("allowed").booleanValue());
        table.setDataScope(new DataScope("domain",List.of("another-domain")));assertFalse(result(call(input("table").toString(),200)).get("allowed").booleanValue());
        for(String kind:List.of("field","row","unknown")){table.setDataScope(new DataScope(kind,List.of("13")));assertFalse(result(call(input("table").toString(),200)).get("allowed").booleanValue());}
        table.setDataScope(new DataScope("table",List.of("13")));assertTrue(result(call(input("table").toString(),200)).get("allowed").booleanValue());
        verifyNoInteractions(decisions);
    }
    static ResourceGrant grant(String resource,long id,String action){var g=new ResourceGrant();g.setResourceType(resource);g.setResourceId(id);g.setActions(List.of(action));g.setEnabled(true);return g;}
}
