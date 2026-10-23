package com.aetheris.aiagent.service;

import com.aetheris.aiagent.security.AiTestKeys;
import com.aetheris.security.protocol.UserProof;
import com.aetheris.security.servlet.*;
import org.springframework.mock.web.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Test identities still pass the actual Ed25519 verifier; there is no production string-owner bridge. */
public final class ChatOwnerFixture {
    private static final AiTestKeys KEYS = new AiTestKeys();
    private ChatOwnerFixture() { }
    public static ChatOwner owner(String name) { return owner("bob".equals(name) ? 8 : 7, name, 3); }
    public static ChatOwner owner(long uid, String name, long av) {
        try {
            long now = KEYS.clock.instant().getEpochSecond();
            var request = new MockHttpServletRequest("GET", "/api/ai/conversations");
            request.addHeader("X-Aetheris-User-Proof", KEYS.user.sign(new UserProof(2, "gateway", "ai-agent", uid, name, av,
                    now, now + 60, "fixture-" + UUID.randomUUID())));
            var result = new AtomicReference<ChatOwner>();
            new DirectUserProofFilter("ai-agent", KEYS.verifier, (u,n,v) -> true, (m,p) -> DirectRoutePolicy.Decision.PROTECTED)
                    .doFilter(request, new MockHttpServletResponse(), (r,s) -> result.set(ChatOwner.from(TrustedUser.require((jakarta.servlet.http.HttpServletRequest)r))));
            if (result.get() == null) throw new IllegalStateException("Fixture proof was not admitted");
            return result.get();
        } catch (Exception invalid) { throw new IllegalStateException("Cannot create verified fixture owner", invalid); }
    }
}
