package com.aetheris.system.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 测试内签名工具：与网关 IdentitySigner 相同协议（供拦截器测试构造合法签名）。 */
final class IdentityVerifierTestHelper {

    private IdentityVerifierTestHelper() {
    }

    static String sign(Long uid, String username, String token) {
        return sign(uid, username, System.currentTimeMillis(), token);
    }

    static String sign(Long uid, String username, long timestamp, String token) {
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