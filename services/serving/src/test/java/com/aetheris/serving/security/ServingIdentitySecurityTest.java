package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServingIdentitySecurityTest {
    private static final String TOKEN = "internal-test-token-with-enough-entropy";

    @Test
    void verifierRejectsBlankAndInvalidUidClaims() throws Exception {
        IdentityVerifier verifier = new IdentityVerifier(TOKEN, 300_000);
        assertEquals(new ServingIdentity(42L, "alice"), verifier.verify(sign("42", "alice", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("0", "alice", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("42", "", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("42", "alice", System.currentTimeMillis() - 301_000)));
    }

    @Test
    void webConfigCachesSignedIdentityAndOnlyApiAssetRouteAllowsApiKey() throws Exception {
        var interceptor = new ServingIdentityInterceptor(new IdentityVerifier(TOKEN, 300_000));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/serving/subscriptions");
        request.addHeader(IdentityVerifier.HEADER, sign("7", "alice", System.currentTimeMillis()));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(new ServingIdentity(7L, "alice"), request.getAttribute(ServingSecurity.ATTRIBUTE));

        MockHttpServletRequest unsigned = new MockHttpServletRequest("GET", "/api/serving/subscriptions");
        unsigned.addHeader("X-API-Key", "presented");
        MockHttpServletResponse unsignedResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(unsigned, unsignedResponse, new Object()));
        assertEquals(401, unsignedResponse.getStatus());

        MockHttpServletRequest api = new MockHttpServletRequest("POST", "/api/serving/v1/apis/sales/query");
        api.addHeader("X-API-Key", "presented");
        MockHttpServletResponse apiResponse = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(api, apiResponse, new Object()));
        assertNull(api.getAttribute(ServingSecurity.ATTRIBUTE));
    }

    private static String sign(String uid, String username, long timestamp) throws Exception {
        String payload = uid + "|" + username + "|" + timestamp;
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
