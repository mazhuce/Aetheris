package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UserRelayPolicyTest {
    static final String PATH="/internal/v2/permissions/check", OP="auth.permission.check";
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(1800000000),ZoneOffset.UTC);
    static ProtocolSigner metadata,workflow,auth,user;
    static ProtocolVerifier verifier;
    @BeforeAll static void keys() throws Exception {
        var gen=KeyPairGenerator.getInstance("Ed25519");var m=gen.generateKeyPair();var w=gen.generateKeyPair();var a=gen.generateKeyPair();var u=gen.generateKeyPair();
        var keys=new KeyRegistry(List.of(new KeyRegistry.Entry("m",KeyPurpose.WORKLOAD,"metadata",m.getPublic()),
                new KeyRegistry.Entry("w",KeyPurpose.WORKLOAD,"workflow",w.getPublic()),new KeyRegistry.Entry("a",KeyPurpose.WORKLOAD,"auth",a.getPublic()),
                new KeyRegistry.Entry("u",KeyPurpose.USER,"gateway",u.getPublic())));
        metadata=new ProtocolSigner("m",KeyPurpose.WORKLOAD,"metadata",m.getPrivate(),keys,CLOCK,TimePolicy.defaults());
        workflow=new ProtocolSigner("w",KeyPurpose.WORKLOAD,"workflow",w.getPrivate(),keys,CLOCK,TimePolicy.defaults());
        auth=new ProtocolSigner("a",KeyPurpose.WORKLOAD,"auth",a.getPrivate(),keys,CLOCK,TimePolicy.defaults());
        user=new ProtocolSigner("u",KeyPurpose.USER,"gateway",u.getPrivate(),keys,CLOCK,TimePolicy.defaults());
        verifier=new ProtocolVerifier(keys,CLOCK,TimePolicy.defaults());
    }
    MockHttpServletResponse run(RoutePolicy.Route route,String caller,String userAudience,AtomicInteger business) throws Exception {
        long now=CLOCK.instant().getEpochSecond();String compact=user.sign(new UserProof(2,"gateway",userAudience,42,"alice",7,now,now+120,"relay-1"));
        byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request=new MockHttpServletRequest("POST",PATH);request.setContent(body);request.addHeader(SignedHttpClient.USER_PROOF,compact);
        request.addHeader(SignedHttpClient.REQUEST_PROOF,(caller.equals("metadata")?metadata:workflow).sign(
                new WorkloadRequestProof(2,caller,"auth","POST",PATH,BodyHash.sha256(body),Nonce.random(),now,now+30,OP,BodyHash.userProof(compact))));
        var filter=new WorkloadProofFilter("auth",verifier,auth,(method,path)->Optional.of(route),(c,n,e)->true,
                (uid,name,av)->uid==42&&name.equals("alice")&&av==7,CLOCK,TimePolicy.defaults());
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{
            business.incrementAndGet();assertEquals(userAudience,TrustedUser.require((jakarta.servlet.http.HttpServletRequest)req).proof().audience());
            assertEquals(compact,TrustedUser.require((jakarta.servlet.http.HttpServletRequest)req).compactProof());
            ((jakarta.servlet.http.HttpServletResponse)res).setStatus(204);
        });return response;
    }
    @Test void defaultCallerAudienceStillRejectsAnotherServicesValidUserProof() throws Exception {
        var business=new AtomicInteger();var route=new RoutePolicy.Route(OP,Set.of("metadata"),RoutePolicy.UserMode.REQUIRED);
        assertEquals(401,run(route,"metadata","serving",business).getStatus());assertEquals(0,business.get());
        assertEquals(204,run(route,"metadata","metadata",business).getStatus());assertEquals(1,business.get());
    }
    @Test void onlyExplicitCallerRouteCanRelayOnlyItsFixedAudienceSet() throws Exception {
        var route=new RoutePolicy.Route(OP,Set.of("metadata","workflow"),RoutePolicy.UserMode.REQUIRED,
                Map.of("metadata",Set.of("metadata","serving","ai-agent")));var business=new AtomicInteger();
        assertEquals(204,run(route,"metadata","serving",business).getStatus());
        assertEquals(204,run(route,"metadata","ai-agent",business).getStatus());
        assertEquals(401,run(route,"metadata","system",business).getStatus());
        assertEquals(401,run(route,"workflow","serving",business).getStatus());
        assertEquals(204,run(route,"workflow","workflow",business).getStatus());assertEquals(3,business.get());
    }
    @Test void relayMapIsDeeplyImmutableAndInvalidPoliciesFailBeforeServing() {
        var audiences=new HashSet<>(Set.of("serving"));var map=new HashMap<String,Set<String>>();map.put("metadata",audiences);
        var route=new RoutePolicy.Route(OP,Set.of("metadata"),RoutePolicy.UserMode.REQUIRED,map);audiences.add("system");map.clear();
        assertEquals(Set.of("serving"),route.userAudiences("metadata"));
        assertThrows(UnsupportedOperationException.class,()->route.userAudiences("metadata").add("system"));
        assertThrows(IllegalArgumentException.class,()->route.userAudiences("workflow"));
        assertThrows(IllegalArgumentException.class,()->new RoutePolicy.Route(OP,Set.of("metadata"),RoutePolicy.UserMode.REQUIRED,Map.of("workflow",Set.of("serving"))));
        assertThrows(IllegalArgumentException.class,()->new RoutePolicy.Route(OP,Set.of("metadata"),RoutePolicy.UserMode.FORBIDDEN,Map.of("metadata",Set.of("serving"))));
        assertThrows(IllegalArgumentException.class,()->new RoutePolicy.Route(OP,Set.of("metadata"),RoutePolicy.UserMode.REQUIRED,Map.of("metadata",Set.of())));
    }
}
