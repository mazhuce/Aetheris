package com.aetheris.gateway.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * 网关 → 业务服务的身份传递签名。
 *
 * <p>网关是唯一的 JWT 验签入口：解析 uid/username 后，用与各服务共享的
 * AETHERIS_INTERNAL_TOKEN 对身份做 HMAC-SHA256 签名，随请求头
 * {@code X-Aetheris-Identity} 转发。业务服务（metadata/serving/governance 等）
 * 据此验证"该身份确实经过网关认证"，而不是信任客户端可伪造的
 * X-User-Name / X-User-Role。
 *
 * <p>协议：{@code base64url(uid|username|tsMillis) + "." + hex(hmacSha256(payload))}
 * 其中 tsMillis 用于下游做新鲜度窗口校验（默认 5 分钟），防止被直接暴露的
 * 服务端口被复用旧签名重放。
 */
@Component
public class IdentitySigner {

    public static final String IDENTITY_HEADER = "X-Aetheris-Identity";

    private final byte[] tokenBytes;
    private final long maxSkewMillis;

    @Autowired
    public IdentitySigner(
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.identity.max-skew-ms:300000}") long maxSkewMillis) {
        this.tokenBytes = internalToken == null
                ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
        requireStrongToken(this.tokenBytes);
        this.maxSkewMillis = maxSkewMillis;
    }

    /** 测试构造：固定令牌。 */
    public IdentitySigner(String internalToken) {
        this.tokenBytes = internalToken == null
                ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
        this.maxSkewMillis = 300_000;
    }

    /** 未配置共享令牌时无法产生可信签名，返回 null（调用方应跳过该头）。 */
    public String sign(Long uid, String username) {
        if (tokenBytes.length == 0) {
            return null;
        }
        return sign(uid, username, Clock.systemUTC().millis());
    }

    String sign(Long uid, String username, long timestamp) {
        String payload = (uid == null ? "-" : uid) + "|" + username + "|" + timestamp;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hex(hmac(encoded));
    }

    /**
     * 校验网关签名并解出身份；令牌缺失、签名不符或时间超出新鲜度窗口均返回 null。
     * 供业务服务侧复用同一协议实现。
     */
    public String[] verify(String headerValue) {
        if (tokenBytes.length == 0 || headerValue == null || headerValue.isBlank()) {
            return null;
        }
        int dot = headerValue.lastIndexOf('.');
        if (dot <= 0 || dot == headerValue.length() - 1) {
            return null;
        }
        String encoded = headerValue.substring(0, dot);
        String providedSignature = headerValue.substring(dot + 1);
        String expected = hex(hmac(encoded));
        if (!MessageDigestEquals.isEqual(expected, providedSignature)) {
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
        try {
            long timestamp = Long.parseLong(parts[2]);
            long now = Clock.systemUTC().millis();
            if (Math.abs(now - timestamp) > maxSkewMillis) {
                return null;
            }
        } catch (NumberFormatException malformed) {
            return null;
        }
        return new String[]{parts[0], parts[1]};
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

    private static void requireStrongToken(byte[] bytes) {
        if (bytes.length > 0 && bytes.length < 32) {
            throw new IllegalStateException("AETHERIS_INTERNAL_TOKEN must be at least 32 UTF-8 bytes");
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    /** 常量时间比较，独立小工具类避免与 auth 依赖耦合。 */
    static final class MessageDigestEquals {
        private MessageDigestEquals() {
        }

        static boolean isEqual(String expected, String actual) {
            byte[] a = expected.getBytes(StandardCharsets.UTF_8);
            byte[] b = actual.getBytes(StandardCharsets.UTF_8);
            int result = a.length ^ b.length;
            for (int i = 0; i < a.length && i < b.length; i++) {
                result |= a[i] ^ b[i];
            }
            return result == 0;
        }
    }
}
