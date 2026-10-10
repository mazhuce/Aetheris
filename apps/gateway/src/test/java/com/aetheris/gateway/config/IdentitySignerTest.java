package com.aetheris.gateway.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdentitySignerTest {

    private static final String TOKEN = "internal-test-token-with-at-least-32-bytes";

    /** 固定时间戳必须落在新鲜度窗口内（测试执行时刻附近）。 */
    private static final long FIXED_TIME = System.currentTimeMillis();

    @Test
    void signsAndVerifiesRoundTrip() {
        IdentitySigner signer = new IdentitySigner(TOKEN);

        String value = signer.sign(42L, "alice", FIXED_TIME);

        assertNotNull(value);
        assertArrayEquals(new String[]{"42", "alice"}, signer.verify(value));
    }

    @Test
    void rejectsTamperedPayload() {
        IdentitySigner signer = new IdentitySigner(TOKEN);
        String value = signer.sign(42L, "alice", FIXED_TIME);
        String tampered = "YXR0YWNrZXI." + value.substring(value.lastIndexOf('.') + 1);

        assertNull(signer.verify(tampered));
    }

    @Test
    void rejectsSignatureFromDifferentToken() {
        String value = new IdentitySigner(TOKEN).sign(42L, "alice", FIXED_TIME);

        assertNull(new IdentitySigner("another-token").verify(value));
    }

    @Test
    void rejectsExpiredIdentityBeyondSkewWindow() {
        IdentitySigner signer = new IdentitySigner(TOKEN);
        long stale = System.currentTimeMillis() - 600_000;

        assertNull(signer.verify(signer.sign(42L, "alice", stale)));
    }

    @Test
    void returnsNullWhenTokenNotConfigured() {
        IdentitySigner signer = new IdentitySigner("");

        assertNull(signer.sign(42L, "alice"));
        assertNull(signer.verify("anything.anything"));
    }

    @Test
    void rejectsMalformedHeaderValues() {
        IdentitySigner signer = new IdentitySigner(TOKEN);

        assertNull(signer.verify(null));
        assertNull(signer.verify(""));
        assertNull(signer.verify("no-dot-here"));
        assertNull(signer.verify("a.b.c"));
    }
}
