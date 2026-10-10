package com.aetheris.metadata.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdentityVerifierTest {
    private static final String TOKEN = "internal-test-token-with-enough-entropy";

    @Test
    void acceptsSharedGatewayProtocolAndRejectsInvalidClaims() throws Exception {
        IdentityVerifier verifier = new IdentityVerifier(TOKEN, 300_000);
        assertEquals(new AetherisIdentity(42L, "alice"),
                verifier.verify(sign("42", "alice", System.currentTimeMillis())));
        assertEquals(new AetherisIdentity(null, "service"),
                verifier.verify(sign("-", "service", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("0", "alice", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("42", "", System.currentTimeMillis())));
        assertNull(verifier.verify(sign("42", "alice", System.currentTimeMillis() - 301_000)));
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
