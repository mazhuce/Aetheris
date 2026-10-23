package com.aetheris.ingestion.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class GatewayIdentityInterceptorTest {
    @Test void unsignedDirectRequestIsRejected() throws Exception {
        var response=new MockHttpServletResponse();
        assertFalse(new GatewayIdentityInterceptor(new ResourceAuthorization()).preHandle(new MockHttpServletRequest("GET","/api/ingestion/tasks"),response,new Object()));
        assertEquals(401,response.getStatus());
    }
    @Test void validLegacySignatureIsNoLongerAnIdentity() throws Exception {
        String secret="internal-test-token-with-enough-entropy";
        String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(("42|alice|"+System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        String legacy=payload+"."+java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        var request=new MockHttpServletRequest("GET","/api/ingestion/tasks");request.addHeader("X-Aetheris-Identity",legacy);
        request.addHeader("X-Internal-Token",secret);request.addHeader("X-User-Name","admin");request.addHeader("X-User-Role","admin");
        var response=new MockHttpServletResponse();assertFalse(new GatewayIdentityInterceptor(new ResourceAuthorization()).preHandle(request,response,new Object()));
        assertEquals(401,response.getStatus());
    }
    @Test void actualProofEstablishesOnlyAliceAndMutationStillRequiresExplicitPermission() throws Exception {
        try(var fixture=new V2IngestionFixture()){
            fixture.request(request->{
                try {assertTrue(new GatewayIdentityInterceptor(fixture.authorization).preHandle(request,new MockHttpServletResponse(),new Object()));}
                catch(Exception error){throw new AssertionError(error);}
                assertEquals("alice",request.getAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE));
            });
            fixture.mutate.set(false);
            var input=new MockHttpServletRequest("PUT","/api/ingestion/tasks/8");input.addHeader("X-Aetheris-User-Proof",fixture.token());
            var response=new MockHttpServletResponse();
            fixture.direct().doFilter(input,response,(r,s)->{
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes((jakarta.servlet.http.HttpServletRequest)r));
                try{assertFalse(new GatewayIdentityInterceptor(fixture.authorization).preHandle((jakarta.servlet.http.HttpServletRequest)r,(jakarta.servlet.http.HttpServletResponse)s,new Object()));}
                finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
            });assertEquals(403,response.getStatus());
        }
    }
}
