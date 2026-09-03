package com.aetheris.system.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与 apps/gateway IdentitySigner 的协议互操作测试：这里用测试内独立实现的
 * 签名函数（与网关实现同格式）证明两侧协议一致。
 */
class IdentityVerifierTest {

    private static final String TOKEN = "internal-test-token";

    @Test
    void verifiesGatewaySignedIdentity() {
        long now = System.currentTimeMillis();
        String header = sign(42L, "alice", now, TOKEN);
        IdentityVerifier verifier = new IdentityVerifier(TOKEN, 300_000);

        AetherisIdentity identity = verifier.verify(header);

        assertEquals(42L, identity.uid());
        assertEquals("alice", identity.username());
    }

    @Test
    void verifiesIdentityWithoutUid() {
        String header = sign(null, "alice", System.currentTimeMillis(), TOKEN);
        IdentityVerifier verifier = new IdentityVerifier(TOKEN, 300_000);

        AetherisIdentity identity = verifier.verify(header);

        assertNull(identity.uid());
        assertEquals("alice", identity.username());
    }

    @Test
    void rejectsWrongTokenSignature() {
        String header = sign(42L, "alice", System.currentTimeMillis(), TOKEN);
        IdentityVerifier verifier = new IdentityVerifier("another-token", 300_000);

        assertNull(verifier.verify(header));
    }

    @Test
    void rejectsTamperedPayload() {
        long now = System.currentTimeMillis();
        String header = sign(42L, "alice", now, TOKEN);
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("42|attacker|" + now).getBytes(StandardCharsets.UTF_8));
        String tampered = tamperedPayload + header.substring(header.lastIndexOf('.'));

        assertNull(new IdentityVerifier(TOKEN, 300_000).verify(tampered));
    }

    @Test
    void rejectsStaleTimestamp() {
        long stale = System.currentTimeMillis() - 600_000;
        String header = sign(42L, "alice", stale, TOKEN);

        assertNull(new IdentityVerifier(TOKEN, 300_000).verify(header));
    }

    @Test
    void rejectsMalformedValues() {
        IdentityVerifier verifier = new IdentityVerifier(TOKEN, 300_000);

        assertNull(verifier.verify((String) null));
        assertNull(verifier.verify(""));
        assertNull(verifier.verify("a.b.c"));
    }

    @Test
    void unconfiguredVerifierRejectsEverything() {
        IdentityVerifier verifier = new IdentityVerifier("", 300_000);

        assertFalse(verifier.isConfigured());
        assertNull(verifier.verify(sign(42L, "alice", System.currentTimeMillis(), TOKEN)));
    }

    private static String sign(Long uid, String username, long timestamp, String token) {
        String payload = (uid == null ? "-" : uid) + "|" + username + "|" + timestamp;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(encoded.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return encoded + "." + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}