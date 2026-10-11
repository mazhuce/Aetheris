package com.aetheris.quality.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;

/**
 * 为没有 HTTP 请求上下文的质量调度任务生成短时服务身份。
 *
 * <p>协议与 Gateway 的 {@code X-Aetheris-Identity} 相同。主体由部署配置提供，
 * 默认使用兼容现有安装的 admin 账号；生产环境应设置为专用的只读服务账号。
 * uid 使用 {@code -} 表示它不是浏览器登录主体，权限仍由 auth-service 按主体名实时判定。
 */
@Component
public class ServiceIdentitySigner {

    private final byte[] tokenBytes;
    private final String principal;

    public ServiceIdentitySigner(
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.identity.service-principal:admin}") String principal) {
        this.tokenBytes = internalToken == null
                ? new byte[0] : internalToken.getBytes(StandardCharsets.UTF_8);
        this.principal = principal == null ? "" : principal.trim();
        if (tokenBytes.length > 0 && tokenBytes.length < 32) {
            throw new IllegalStateException("AETHERIS_INTERNAL_TOKEN must be at least 32 UTF-8 bytes");
        }
        if (this.principal.contains("|")) {
            throw new IllegalStateException("AETHERIS_QUALITY_SERVICE_PRINCIPAL must not contain '|'");
        }
    }

    /** 返回当前时间签名；未配置共享令牌或主体时返回空串，调用方会 fail-closed。 */
    public String sign() {
        if (tokenBytes.length == 0 || principal.isBlank()) return "";
        return sign(principal, Clock.systemUTC().millis());
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
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format("%02x", value));
        return builder.toString();
    }
}
