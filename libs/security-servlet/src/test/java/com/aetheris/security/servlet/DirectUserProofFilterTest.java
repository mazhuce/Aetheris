package com.aetheris.security.servlet;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectUserProofFilterTest {
    static final long NOW=1800000000;
    static final Clock CLOCK=Clock.fixed(Instant.ofEpochSecond(NOW),ZoneOffset.UTC);
    static ProtocolSigner gateway;
    static ProtocolVerifier verifier;
    @BeforeAll static void keys() throws Exception {
        var keys=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        registry=new KeyRegistry(List.of(new KeyRegistry.Entry("gateway-1",KeyPurpose.USER,"gateway",keys.getPublic())));
        gateway=new ProtocolSigner("gateway-1",KeyPurpose.USER,"gateway",keys.getPrivate(),registry,CLOCK,TimePolicy.defaults());
        verifier=new ProtocolVerifier(registry,CLOCK,TimePolicy.defaults());
    }
    String proof(String audience,long av){return gateway.sign(new UserProof(2,"gateway",audience,7,"alice",av,NOW,NOW+120,"request-direct"));}
    DirectUserProofFilter filter(SessionValidator sessions,DirectRoutePolicy routes){return new DirectUserProofFilter("metadata",verifier,sessions,routes);}
    DirectRoutePolicy protectedPolicy(){return (method,path)->DirectRoutePolicy.Decision.PROTECTED;}
    MockHttpServletRequest request(){var input=new MockHttpServletRequest("GET","/api/tables");input.addHeader(SignedHttpClient.USER_PROOF,proof("metadata",3));return input;}
    @Test void directProofProducesSameTrustedTypeAndHidesLegacyIdentity() throws Exception {
        var input=request();input.addHeader("X-User-Id","999");input.addHeader("X-User-Role","admin");input.addHeader("X-Aetheris-Identity","old-HMAC");
        AtomicInteger live=new AtomicInteger(),calls=new AtomicInteger();
        var filter=filter((id,name,av)->{live.incrementAndGet();return id==7&&name.equals("alice")&&av==3;},protectedPolicy());
        filter.doFilter(input,new MockHttpServletResponse(),(req,res)->{
            var r=(HttpServletRequest)req;var trusted=TrustedUser.require(r);assertEquals(7,trusted.proof().uid());assertEquals(proof("metadata",3),trusted.compactProof());
            assertNull(r.getHeader("X-User-Role"));assertEquals(-1,r.getIntHeader("X-User-Id"));assertNull(r.getHeader("X-Aetheris-Identity"));
            assertNull(r.getHeader(SignedHttpClient.USER_PROOF));assertThrows(IllegalStateException.class,()->r.setAttribute(TrustedUser.ATTRIBUTE,"forged"));calls.incrementAndGet();
        });assertEquals(1,live.get());assertEquals(1,calls.get());
    }
    @Test void missingDuplicateOldWrongAudienceAndVersionFailClosed() throws Exception {
        for(String mode:List.of("missing","duplicate","old","audience","version","length")){
            var input=request();if(mode.equals("missing")||mode.equals("old"))input.removeHeader(SignedHttpClient.USER_PROOF);
            if(mode.equals("old"))input.addHeader("X-Aetheris-Identity","old-valid-shared-HMAC");
            if(mode.equals("duplicate"))input.addHeader(SignedHttpClient.USER_PROOF,proof("metadata",3));
            if(mode.equals("audience")||mode.equals("version")||mode.equals("length")){input.removeHeader(SignedHttpClient.USER_PROOF);input.addHeader(SignedHttpClient.USER_PROOF,mode.equals("length")?"x".repeat(12289):proof(mode.equals("audience")?"serving":"metadata",mode.equals("version")?4:3));}
            var response=new MockHttpServletResponse();filter((id,name,av)->av==3,protectedPolicy()).doFilter(input,response,(req,res)->fail("Rejected direct user reached chain"));
            assertEquals(mode.equals("version")?403:401,response.getStatus());assertNull(input.getAttribute(TrustedUser.ATTRIBUTE));
        }
    }
    @Test void disabledAndUnavailableSessionAreDifferentFailures() throws Exception {
        for(boolean unavailable:List.of(false,true)){
            var response=new MockHttpServletResponse();filter((id,name,av)->{if(unavailable)throw new IllegalStateException("private database details");return false;},protectedPolicy()).doFilter(request(),response,(req,res)->fail("Inactive session reached chain"));
            assertEquals(unavailable?503:403,response.getStatus());assertFalse(response.getContentAsString().contains("database"));
        }
    }
    @Test void onlyExactPolicyExemptionsArePublicAndUnknownPolicyNeverFallsThrough() throws Exception {
        var explicit=filter((id,name,av)->true,(method,path)->method.equals("POST")&&path.equals("/api/auth/login")?DirectRoutePolicy.Decision.PUBLIC:DirectRoutePolicy.Decision.PROTECTED);
        AtomicInteger called=new AtomicInteger();explicit.doFilter(new MockHttpServletRequest("POST","/api/auth/login"),new MockHttpServletResponse(),(req,res)->called.incrementAndGet());assertEquals(1,called.get());
        for(String path:List.of("/api/auth/login/admin","/api/auth/login/","/api/%61uth/login")){
            var response=new MockHttpServletResponse();explicit.doFilter(new MockHttpServletRequest("POST",path),response,(req,res)->fail("Adjacent route exempted"));assertEquals(401,response.getStatus());
        }
        for(DirectRoutePolicy route:List.<DirectRoutePolicy>of((method,path)->null,(method,path)->DirectRoutePolicy.Decision.REJECT,(method,path)->{throw new IllegalStateException();})){
            var response=new MockHttpServletResponse();filter((id,name,av)->true,route).doFilter(request(),response,(req,res)->fail("Unknown route policy reached chain"));assertTrue(response.getStatus()>=400);
        }
    }
    @Test void expiredUserProofIsRejectedByFilterBeforeSessionCheck() throws Exception {
        var input=request();var response=new MockHttpServletResponse();
        var expiredVerifier=new ProtocolVerifier(registry,Clock.fixed(Instant.ofEpochSecond(NOW+121),ZoneOffset.UTC),TimePolicy.defaults());
        new DirectUserProofFilter("metadata",expiredVerifier,(id,name,av)->{fail("Expired proof reached live session");return true;},protectedPolicy()).doFilter(input,response,(req,res)->fail("Expired proof reached chain"));assertEquals(401,response.getStatus());
    }
    static KeyRegistry registry;
}
