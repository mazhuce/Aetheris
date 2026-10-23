package com.aetheris.gateway.config;

import com.aetheris.security.protocol.*;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.util.List;

final class GatewayTestKeys {
    static final Clock CLOCK = Clock.systemUTC();
    static final ProtocolSigner SIGNER;
    static final ProtocolVerifier VERIFIER;
    static {
        try {
            var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var registry = new KeyRegistry(List.of(new KeyRegistry.Entry("test-user", KeyPurpose.USER, "gateway", key.getPublic())));
            SIGNER = new ProtocolSigner("test-user", KeyPurpose.USER, "gateway", key.getPrivate(), registry, CLOCK, TimePolicy.defaults());
            VERIFIER = new ProtocolVerifier(registry, CLOCK, TimePolicy.defaults());
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static IdentitySigner identitySigner() { return new IdentitySigner(SIGNER, CLOCK); }
    static UserProof user(String token, String audience) { return VERIFIER.verifyUser(token, audience).proof(); }
}
