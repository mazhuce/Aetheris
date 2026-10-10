package com.aetheris.aiagent.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * 验证 Gateway 的 X-Aetheris-Identity 签名。
 * 协议：base64url(uid|username|timestampMillis) + "." + hex(HMAC-SHA256(payload, token))。
 */
@Component
public class IdentityVerifier {
    public static final String HEADER = "X-Aetheris-Identity";

    private final byte[] token;
    private final long maxSkewMillis;

    public IdentityVerifier(
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.identity.max-skew-ms:300000}") long maxSkewMillis) {
        this.token = internalToken == null ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
        if (token.length > 0 && token.length < 32) {
            throw new IllegalStateException("AETHERIS_INTERNAL_TOKEN must be at least 32 UTF-8 bytes");
        }
        this.maxSkewMillis = maxSkewMillis;
    }

    public boolean configured() {
        return token.length > 0;
    }

    public AetherisIdentity verify(HttpServletRequest request) {
        return request == null ? null : verify(request.getHeader(HEADER));
    }

    public AetherisIdentity verify(String value) {
        if (!configured() || value == null || value.isBlank()) return null;
        int dot = value.lastIndexOf('.');
        if (dot <= 0 || dot == value.length() - 1) return null;
        String encoded = value.substring(0, dot);
        if (!constantTimeEquals(hex(hmac(encoded)), value.substring(dot + 1))) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 3 || parts[1].isBlank()) return null;
            long timestamp = Long.parseLong(parts[2]);
            if (Math.abs(Clock.systemUTC().millis() - timestamp) > maxSkewMillis) return null;
            Long uid = "-".equals(parts[0]) ? null : Long.parseLong(parts[0]);
            if (uid != null && uid <= 0) return null;
            return new AetherisIdentity(uid, parts[1]);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("HMAC 初始化失败", error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        int result = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }
}
