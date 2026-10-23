package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServingV2AuthorizationTest {
    @Test void currentTypedProofControlsDecisionsAndPrivateCache()throws Exception{
        try(var f=new V2ServingFixture()){
            f.request(request->{
                request.setAttribute(ServingSecurity.ATTRIBUTE,new ServingIdentity(1L,"admin"));
                request.setAttribute(ServingSecurity.class.getName()+".permissions",Boolean.TRUE);
                assertEquals("alice",f.authorization.requireIdentity(request).username());
                f.authorization.require(request,"dashboard",8L,"view");
                f.authorization.require(request,"dashboard",8L,"view");
                assertEquals(1,f.requests.stream().filter(r->r.operation().equals("auth.permission.check")).count());
                assertThrows(ServingSecurity.ForbiddenException.class,()->f.authorization.require(request,"dashboard",9L,"view"));
                assertThrows(ServingSecurity.ForbiddenException.class,()->f.authorization.requireManage(request));
                assertThrows(ServingSecurity.ForbiddenException.class,()->f.authorization.require(request,"auth_user",8L,"delete"));
            });
        }
    }
    @Test void revokedGrantAndNewRequestCannotReuseAnOlderAllow()throws Exception{
        try(var f=new V2ServingFixture()){
            f.request(request->f.authorization.require(request,"table",8L,"query"));
            f.allowed.set(false);
            f.request(request->assertThrows(ServingSecurity.ForbiddenException.class,()->f.authorization.require(request,"table",8L,"query")));
            f.allowed.set(true);f.fault="tampered";f.permissionFaultOnly=true;
            f.request(request->assertEquals(503,assertThrows(ResponseStatusException.class,()->f.authorization.require(request,"table",8L,"query")).getStatusCode().value()));
        }
    }
    @Test void listsUseBoundedBatchesAndNeverReturnUnrequestedRows()throws Exception{
        try(var f=new V2ServingFixture()){
            var ids=java.util.stream.LongStream.rangeClosed(1,1001).boxed().toList();
            f.request(request->assertEquals(List.of(8L),f.authorization.filter(request,"dataset",ids,id->id)));
            var batches=f.requests.stream().filter(r->r.operation().equals("auth.permission.batch")).toList();
            assertEquals(3,batches.size());assertEquals(List.of(500,500,1),batches.stream().map(r->r.body().path("items").size()).toList());
        }
    }
}
