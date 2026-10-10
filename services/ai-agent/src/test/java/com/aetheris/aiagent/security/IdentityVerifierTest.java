package com.aetheris.aiagent.security;

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

class IdentityVerifierTest {
    private static final String TOKEN = "internal-test-token-with-enough-entropy";

    @Test
    void verifiesSignedIdentityAndRejectsMalformedOrStaleValues() throws Exception {
        IdentityVerifier verifier = new IdentityVerifier(TOKEN, 300_000);
        AetherisIdentity identity = verifier.verify(sign("42", "alice", System.currentTimeMillis()));
        assertEquals(42L, identity.uid());
        assertEquals("alice", identity.username());
        assertNull(verifier.verify(sign("0", "alice", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("42", "", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("42", "alice", System.currentTimeMillis() - 301_000)));
        assertNull(verifier.verify("not-a-signed-value"));
    }

    @Test
    void interceptorCachesOnlyVerifiedIdentityAndFailsClosed() throws Exception {
        IdentityInterceptor interceptor = new IdentityInterceptor(new IdentityVerifier(TOKEN, 300_000));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ai/chat");
        request.addHeader(IdentityVerifier.HEADER, sign("7", "alice", System.currentTimeMillis()));
        request.addHeader("X-User-Name", "attacker");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(new AetherisIdentity(7L, "alice"),
                request.getAttribute(IdentityInterceptor.IDENTITY_ATTRIBUTE));

        MockHttpServletRequest forged = new MockHttpServletRequest("GET", "/api/ai/chat");
        forged.addHeader(IdentityVerifier.HEADER, "forged");
        MockHttpServletResponse forgedResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(forged, forgedResponse, new Object()));
        assertEquals(401, forgedResponse.getStatus());
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
