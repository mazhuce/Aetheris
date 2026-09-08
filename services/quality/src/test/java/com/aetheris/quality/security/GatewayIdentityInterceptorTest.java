package com.aetheris.quality.security;

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
    private static final String TOKEN = "internal-test-token-with-enough-entropy";

    @Test
    void unsignedDirectRequestIsRejected() throws Exception {
        GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(TOKEN, 300_000, user -> true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/test"), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void forgedRoleCannotGrantMutationPermission() throws Exception {
        GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(TOKEN, 300_000, user -> false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/test");
        request.addHeader(GatewayIdentityInterceptor.IDENTITY_HEADER, sign("42", "viewer"));
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void signedReadAndAuthorizedMutationPass() throws Exception {
        GatewayIdentityInterceptor interceptor = new GatewayIdentityInterceptor(TOKEN, 300_000, user -> true);
        for (String method : new String[]{"GET", "POST"}) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/test");
            request.addHeader(GatewayIdentityInterceptor.IDENTITY_HEADER, sign("1", "admin"));
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertEquals("admin", request.getAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE));
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
