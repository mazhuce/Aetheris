package com.aetheris.gateway.config;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.*;

/** Gateway exclusively owns the USER private key. No shared-secret identity signer. */
@Component
public final class IdentitySigner {
    public static final String IDENTITY_HEADER = "X-Aetheris-User-Proof";
    private final ProtocolSigner signer;
    private final Clock clock;
    @Autowired
    public IdentitySigner(ProtocolInstallation installation) {
        this(installation.userSigner().orElseThrow(() -> new IllegalStateException("Gateway USER signing key required")), Clock.systemUTC());
    }
    IdentitySigner(ProtocolSigner signer, Clock clock) { this.signer = signer; this.clock = clock; }
    public String sign(Long uid, String username, long accessVersion, String audience, String requestId, Instant accessExpiresAt) {
        long now = clock.instant().getEpochSecond();
        long expires = Math.min(now + 120, accessExpiresAt.getEpochSecond());
        return signer.sign(new UserProof(2, "gateway", audience, uid, username, accessVersion, now, expires, requestId));
    }
}
