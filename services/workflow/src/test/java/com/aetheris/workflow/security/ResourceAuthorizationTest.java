package com.aetheris.workflow.security;

import com.aetheris.workflow.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ResourceAuthorizationTest {
    @Test void noContextOrBareUsernameCannotChooseAnAuthorizationSubject() throws Exception {
        try(var f=new V2WorkflowFixture()){
            assertFalse(f.authorization.allowed("workflow_task",8L,"view"));assertFalse(f.authorization.mayManage());
            var raw=new MockHttpServletRequest();raw.setAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE,"alice");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(raw));
            assertFalse(f.authorization.allowed("workflow_task",8L,"view"));assertTrue(f.requests.isEmpty());
        }
    }
    @Test void exactResourceActionAndOriginalProofAreSentWithoutUsernameBody() throws Exception {
        try(var f=new V2WorkflowFixture()){
            f.request(r->{String exact=com.aetheris.security.servlet.TrustedUser.require(r).compactProof();
                assertTrue(f.authorization.allowed("workflow_task",8L,"execute"));assertEquals(exact,f.requests.getLast().userProof());
                assertFalse(f.authorization.allowed("workflow_task",9L,"execute"));
                assertFalse(f.authorization.allowed("workflow_task",-1L,"view"));
                assertFalse(f.authorization.allowed("table",8L,"query"));});
            var records=f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).toList();
            assertEquals(2,records.size());assertEquals("execute",records.getFirst().body().get("action").asText());
            assertEquals("workflow",f.verifier.verifyUser(records.getFirst().userProof(),"workflow").proof().audience());
        }
    }
    @Test void decisionsAreReusedWithinOneRequestAndRevocationIsObservedOnTheNext() throws Exception {
        try(var f=new V2WorkflowFixture()){
            f.request(r->{assertTrue(f.authorization.allowed("workflow_task",8L,"view"));r.setAttribute(ResourceAuthorization.class.getName()+":workflow_task:9:view",true);assertFalse(f.authorization.allowed("workflow_task",9L,"view"));f.allowed.set(false);assertTrue(f.authorization.allowed("workflow_task",8L,"view"));});
            f.request(r->assertFalse(f.authorization.allowed("workflow_task",8L,"view")));
            assertEquals(3,f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).count());
        }
    }
    @Test void batchOutOfBoundsAndUntrustedOrFailedAuthResponsesNeverYieldRows() throws Exception {
        try(var f=new V2WorkflowFixture()){
            f.request(r->assertEquals(List.of(8L),f.authorization.filter("workflow_task",List.of(8L,9L),v->v)));
            f.outsideBatch=true;
            f.request(r->assertEquals(503,assertThrows(ResponseStatusException.class,()->f.authorization.filter("workflow_task",List.of(8L),v->v)).getStatusCode().value()));
            f.outsideBatch=false;
            for(String fault:List.of("unsigned","tampered","signed500","wrongCaller","wrongPurpose"))f.request(r->{f.fault=fault;
                assertEquals(503,assertThrows(ResponseStatusException.class,()->f.authorization.allowed("workflow_task",8L,"view")).getStatusCode().value());f.fault="";});
        }
    }
    @Test void mismatchedAttributeCannotReplaceTheVerifiedUsername() throws Exception {
        try(var f=new V2WorkflowFixture()){
            f.request(r->{r.setAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE,"admin");
                assertEquals(403,assertThrows(ResponseStatusException.class,()->f.authorization.allowed("workflow_task",8L,"view")).getStatusCode().value());});
            assertEquals(1,f.requests.size()); // only the live session lookup
        }
    }
    @Test void largeListUsesBoundedBatchesWithNoReturnedIdOutsideRequestedItems() throws Exception {
        try(var f=new V2WorkflowFixture()){
            var values=java.util.stream.LongStream.rangeClosed(1,501).boxed().toList();
            f.request(r->assertEquals(List.of(8L),f.authorization.filter("workflow_task",values,v->v)));
            var batches=f.requests.stream().filter(r->r.operation().equals("auth.permission.batch")).toList();
            assertEquals(2,batches.size());assertEquals(500,batches.get(0).body().get("items").size());assertEquals(1,batches.get(1).body().get("items").size());
        }
    }
    @Test void delegatedUpdateRetainsDefinitionGuardWithoutGrantingManage() throws Exception {
        try(var f=new V2WorkflowFixture()){
            f.request(r->{var old=new Task();old.setId(8L);old.setTaskSql("persisted");
                var malicious=new Task();malicious.setTaskSql("replacement");
                assertThrows(ResponseStatusException.class,()->f.authorization.protectDefinition(malicious,old,Set.of("name")));
                var rename=new Task();rename.setName("renamed");f.authorization.protectDefinition(rename,old,Set.of("name"));
                assertEquals("persisted",rename.getTaskSql());f.authorization.retainEditable(rename,Set.of("name"));
                assertNull(rename.getTaskSql());assertEquals("renamed",rename.getName());});
        }
    }    @Test void copiedPrivateCacheCannotGrantToAnotherTrustedUserInstance() throws Exception {
        try(var f=new V2WorkflowFixture()){var saved=new java.util.concurrent.atomic.AtomicReference<Object>();String key=ResourceAuthorization.class.getName()+".proofDecisions";
            f.request(r->{assertTrue(f.authorization.allowed("workflow_task",8L,"view"));saved.set(r.getAttribute(key));});f.allowed.set(false);
            f.request(r->{r.setAttribute(key,saved.get());assertFalse(f.authorization.allowed("workflow_task",8L,"view"));});
            assertEquals(2,f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).count());}
    }

}
