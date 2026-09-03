package com.aetheris.system.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * 网关身份签名验签器（与 apps/gateway IdentitySigner 同一协议）：
 * {@code base64url(uid|username|tsMillis) + "." + hex(HMAC-SHA256(payload, token))}。
 *
 * <p>业务服务据此确认"该身份确实经过网关 JWT 验签"，而不是信任客户端可
 * 伪造的 X-User-Name / X-User-Role。时间戳必须在新鲜度窗口内（默认 5 分钟），
 * 防止直接暴露的服务端口被旧签名重放。
 */
@Component
public class IdentityVerifier {

    public static final String IDENTITY_HEADER = "X-Aetheris-Identity";

    private final byte[] tokenBytes;
    private final long maxSkewMillis;

    public IdentityVerifier(
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.identity.max-skew-ms:300000}") long maxSkewMillis) {
        this.tokenBytes = internalToken == null
                ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
        this.maxSkewMillis = maxSkewMillis;
    }

    public boolean isConfigured() {
        return tokenBytes.length > 0;
    }

    /** 验签并解出身份；令牌缺失、签名不符、超时窗口均返回 null。 */
    public AetherisIdentity verify(String headerValue) {
        if (!isConfigured() || headerValue == null || headerValue.isBlank()) {
            return null;
        }
        int dot = headerValue.lastIndexOf('.');
        if (dot <= 0 || dot == headerValue.length() - 1) {
            return null;
        }
        String encoded = headerValue.substring(0, dot);
        String providedSignature = headerValue.substring(dot + 1);
        if (!constantTimeEquals(hex(hmac(encoded)), providedSignature)) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        String[] parts = payload.split("\\|", -1);
        if (parts.length != 3) {
            return null;
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[2]);
        } catch (NumberFormatException malformed) {
            return null;
        }
        if (Math.abs(Clock.systemUTC().millis() - timestamp) > maxSkewMillis) {
            return null;
        }
        Long uid = "-".equals(parts[0]) ? null : parseUid(parts[0]);
        if (!"-".equals(parts[0]) && uid == null) {
            return null;
        }
        return new AetherisIdentity(uid, parts[1]);
    }

    /** 从请求头验签（与 verify 相同，仅简化调用方）。 */
    public AetherisIdentity verify(HttpServletRequest request) {
        return request == null ? null : verify(request.getHeader(IDENTITY_HEADER));
    }

    private static Long parseUid(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenBytes, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(StandardCharsets.UTF_8);
        int result = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }
}