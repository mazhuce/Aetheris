package com.aetheris.gateway.config;

import com.aetheris.security.protocol.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentitySignerTest {
    @Test void bindsStableUserAudienceVersionAndRequestId() {
        String token = GatewayTestKeys.identitySigner().sign(42L, "alice", 7, "metadata", "request-123", Instant.now().plusSeconds(300));
        UserProof proof = GatewayTestKeys.user(token, "metadata");
        assertEquals(42L, proof.uid()); assertEquals("alice", proof.username()); assertEquals(7, proof.accessVersion());
        assertEquals("request-123", proof.requestId()); assertEquals(120, proof.expiresAt() - proof.issuedAt());
        assertThrows(ProtocolException.class, () -> GatewayTestKeys.user(token, "serving"));
    }
    @Test void cannotExtendBeyondJwtExpiry() {
        Instant expires = Instant.now().plusSeconds(20);
        var token = GatewayTestKeys.identitySigner().sign(42L, "alice", 0, "system", "request-123", expires);
        assertEquals(expires.getEpochSecond(), GatewayTestKeys.user(token, "system").expiresAt());
    }
    @Test void invalidClaimsFailInsteadOfProducingAnIdentity() {
        var signer = GatewayTestKeys.identitySigner(); var expires = Instant.now().plusSeconds(60);
        assertThrows(RuntimeException.class, () -> signer.sign(0L, "alice", 0, "metadata", "r", expires));
        assertThrows(RuntimeException.class, () -> signer.sign(42L, "alice", -1, "metadata", "r", expires));
        assertThrows(RuntimeException.class, () -> signer.sign(42L, "alice", 0, "metadata", "r", Instant.now().minusSeconds(5)));
        assertThrows(RuntimeException.class, () -> signer.sign(42L, "alice", 0, "metadata", "bad id", expires));
    }
    @Test void legacyHmacAndBareHeadersCannotBecomeV2Identity() {
        assertThrows(ProtocolException.class, () -> GatewayTestKeys.user("NDJ8YWxpY2V8MQ.signature", "metadata"));
        assertThrows(ProtocolException.class, () -> GatewayTestKeys.user("alice", "metadata"));
    }
}
