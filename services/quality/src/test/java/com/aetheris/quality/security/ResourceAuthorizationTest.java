package com.aetheris.quality.security;

import com.aetheris.quality.entity.QualityRule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceAuthorizationTest {
    @Test void noContextOrBareUsernameCannotChooseAnAuthorizationSubject() throws Exception {
        try(var f=new V2QualityFixture()){
            assertFalse(f.authorization.allowed("quality_rule",8L,"view"));assertFalse(f.authorization.mayManage());
            var raw=new MockHttpServletRequest();raw.setAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE,"alice");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(raw));
            assertFalse(f.authorization.allowed("quality_rule",8L,"view"));assertTrue(f.requests.isEmpty());
        }
    }
    @Test void exactResourceActionAndOriginalProofAreSentWithoutUsernameBody() throws Exception {
        try(var f=new V2QualityFixture()){
            f.request(r->{String exact=com.aetheris.security.servlet.TrustedUser.require(r).compactProof();
                assertTrue(f.authorization.allowed("quality_rule",8L,"execute"));assertEquals(exact,f.requests.getLast().userProof());
                assertFalse(f.authorization.allowed("quality_rule",9L,"execute"));
                assertFalse(f.authorization.allowed("quality_rule",-1L,"view"));
                assertFalse(f.authorization.allowed("table",8L,"query"));});
            var records=f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).toList();
            assertEquals(2,records.size());assertEquals("execute",records.getFirst().body().get("action").asText());
            assertEquals("quality",f.verifier.verifyUser(records.getFirst().userProof(),"quality").proof().audience());
        }
    }
    @Test void decisionsAreReusedWithinOneRequestAndRevocationIsObservedOnTheNext() throws Exception {
        try(var f=new V2QualityFixture()){
            f.request(r->{assertTrue(f.authorization.allowed("quality_rule",8L,"view"));r.setAttribute(ResourceAuthorization.class.getName()+":quality_rule:9:view",true);assertFalse(f.authorization.allowed("quality_rule",9L,"view"));f.allowed.set(false);assertTrue(f.authorization.allowed("quality_rule",8L,"view"));});
            f.request(r->assertFalse(f.authorization.allowed("quality_rule",8L,"view")));
            assertEquals(3,f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).count());
        }
    }
    @Test void batchOutOfBoundsAndUntrustedOrFailedAuthResponsesNeverYieldRows() throws Exception {
        try(var f=new V2QualityFixture()){
            f.request(r->assertEquals(List.of(8L),f.authorization.filter("quality_rule",List.of(8L,9L),v->v)));
            f.outsideBatch=true;
            f.request(r->assertEquals(503,assertThrows(ResponseStatusException.class,()->f.authorization.filter("quality_rule",List.of(8L),v->v)).getStatusCode().value()));
            f.outsideBatch=false;
            for(String fault:List.of("unsigned","tampered","signed500","wrongCaller","wrongPurpose"))f.request(r->{f.fault=fault;
                assertEquals(503,assertThrows(ResponseStatusException.class,()->f.authorization.allowed("quality_rule",8L,"view")).getStatusCode().value());f.fault="";});
        }
    }
    @Test void mismatchedAttributeCannotReplaceTheVerifiedUsername() throws Exception {
        try(var f=new V2QualityFixture()){
            f.request(r->{r.setAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE,"admin");
                assertEquals(403,assertThrows(ResponseStatusException.class,()->f.authorization.allowed("quality_rule",8L,"view")).getStatusCode().value());});
            assertEquals(1,f.requests.size()); // only the live session lookup
        }
    }
    @Test void largeListUsesBoundedBatchesWithNoReturnedIdOutsideRequestedItems() throws Exception {
        try(var f=new V2QualityFixture()){
            var values=java.util.stream.LongStream.rangeClosed(1,501).boxed().toList();
            f.request(r->assertEquals(List.of(8L),f.authorization.filter("quality_rule",values,v->v)));
            var batches=f.requests.stream().filter(r->r.operation().equals("auth.permission.batch")).toList();
            assertEquals(2,batches.size());assertEquals(500,batches.get(0).body().get("items").size());assertEquals(1,batches.get(1).body().get("items").size());
        }
    }
    @Test void delegatedUpdateRetainsDefinitionGuardWithoutGrantingManage() throws Exception {
        try(var f=new V2QualityFixture()){
            f.request(r->{var old=new QualityRule();old.setId(8L);old.setExpression("persisted");
                var malicious=new QualityRule();malicious.setExpression("replacement");
                assertThrows(ResponseStatusException.class,()->f.authorization.protectDefinition(malicious,old,Set.of("name")));
                var rename=new QualityRule();rename.setName("renamed");f.authorization.protectDefinition(rename,old,Set.of("name"));
                assertEquals("persisted",rename.getExpression());f.authorization.retainEditable(rename,Set.of("name"));
                assertNull(rename.getExpression());assertEquals("renamed",rename.getName());});
        }
    }
}
