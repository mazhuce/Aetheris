package com.aetheris.serving.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * 为 serving 已完成 API-key 认证的调用生成下游可验证身份。
 *
 * <p>API key 不经过 Gateway JWT，因此不能把客户端请求头原样传给 metadata。
 * serving 已在本地校验 key 和 owner 后，用共享内部令牌重新签名 owner 身份；
 * metadata 仍按同一套权限和审计链路处理该主体。
 */
@Component
public class ServiceIdentitySigner {

    private final byte[] tokenBytes;

    public ServiceIdentitySigner(@Value("${aetheris.internal-token:}") String internalToken) {
        this.tokenBytes = internalToken == null
                ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
        if (tokenBytes.length > 0 && tokenBytes.length < 32) {
            throw new IllegalStateException("AETHERIS_INTERNAL_TOKEN must be at least 32 UTF-8 bytes");
        }
    }

    /** 未配置共享令牌时返回空串，调用方会让下游 fail-closed。 */
    public String sign(String username) {
        if (tokenBytes.length == 0 || username == null || username.isBlank() || username.contains("|")) {
            return "";
        }
        return sign(username.trim(), Clock.systemUTC().millis());
    }

    String sign(String username, long timestamp) {
        String payload = "-|" + username + "|" + timestamp;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + hex(hmac(encoded));
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenBytes, "HmacSHA256"));
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
}
