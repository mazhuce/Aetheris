package com.aetheris.workflow.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayIdentityInterceptorTest {
    @Test void legacyHmacCannotEstablishWorkflowApiSubject() throws Exception {
        var interceptor=new GatewayIdentityInterceptor(new ResourceAuthorization());
        var request=new MockHttpServletRequest("GET","/api/workflow/tasks");request.addHeader(GatewayIdentityInterceptor.IDENTITY_HEADER,sign("42","alice"));
        assertFalse(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }
    private static final String TOKEN = "internal-test-token-with-enough-entropy";

    @Test
    void unsignedDirectRequestIsRejected() throws Exception {
        GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(new ResourceAuthorization());
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/test"), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void forgedRoleCannotGrantMutationPermission() throws Exception {
        GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(new ResourceAuthorization());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader(GatewayIdentityInterceptor.IDENTITY_HEADER, sign("42", "viewer"));
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void signedReadAndAuthorizedMutationPass() throws Exception {
        try(var fixture=new V2WorkflowFixture()) {
            GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(fixture.authorization);
            fixture.manage.set(true);
            fixture.request(request->{
                for(String method:java.util.List.of("GET","POST")) {
                    ((org.springframework.mock.web.MockHttpServletRequest)((jakarta.servlet.http.HttpServletRequestWrapper)request).getRequest()).setMethod(method);
                    var response=new MockHttpServletResponse();
                    try {assertTrue(interceptor.preHandle(request,response,new Object()));}catch(Exception error){throw new RuntimeException(error);}
                    assertEquals("alice",request.getAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE));
                }
            });
        }
    }
    @Test void adjacentRulePrefixCannotUseCoarseMutationInsteadOfManage() throws Exception {
        try(var f=new V2WorkflowFixture()) {
            f.mutate.set(true);f.manage.set(false);var interceptor=new GatewayIdentityInterceptor(f.authorization);
            f.request(request->{var raw=(org.springframework.mock.web.MockHttpServletRequest)((jakarta.servlet.http.HttpServletRequestWrapper)request).getRequest();
                raw.setMethod("POST");raw.setRequestURI("/api/workflow/tasks-neighbor");var response=new MockHttpServletResponse();
                try {assertFalse(interceptor.preHandle(request,response,new Object()));}catch(Exception e){throw new RuntimeException(e);}assertEquals(403,response.getStatus());
            });
            assertTrue(f.requests.stream().anyMatch(r->r.operation().equals("auth.permission.manage")));
            assertFalse(f.requests.stream().anyMatch(r->r.operation().equals("auth.permission.mutate")));
        }
    }

    private static String sign(String uid, String username) throws Exception {
        String payload = uid + "|" + username + "|" + System.currentTimeMillis();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder signature = new StringBuilder();
        for (byte value : mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8))) {
            signature.append(String.format("%02x", value));
        }
        return encoded + "." + signature;
    }
}
