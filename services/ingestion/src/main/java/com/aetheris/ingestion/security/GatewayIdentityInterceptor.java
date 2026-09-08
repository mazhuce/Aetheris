package com.aetheris.ingestion.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

/** Verifies the gateway-signed identity and asks auth for every mutating request. */
@Component
public class GatewayIdentityInterceptor implements HandlerInterceptor {

    public static final String IDENTITY_HEADER = "X-Aetheris-Identity";
    public static final String USERNAME_ATTRIBUTE = "aetheris.username";

    private final byte[] tokenBytes;
    private final long maxSkewMillis;
    private final Function<String, Boolean> mayManage;

    @Autowired
    public GatewayIdentityInterceptor(
            RestClient.Builder builder,
            @Value("${aetheris.internal-token:}") String internalToken,
            @Value("${aetheris.identity.max-skew-ms:300000}") long maxSkewMillis,
            @Value("${aetheris.auth.base-url:http://localhost:8087}") String authBaseUrl) {
        this.tokenBytes = bytes(internalToken);
        this.maxSkewMillis = maxSkewMillis;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(1500);
        RestClient auth = builder.requestFactory(factory).baseUrl(authBaseUrl)
                .defaultHeader("X-Internal-Token", internalToken == null ? "" : internalToken).build();
        this.mayManage = username -> mayManage(auth, username);
    }

    GatewayIdentityInterceptor(String internalToken, long maxSkewMillis,
                               Function<String, Boolean> mayManage) {
        this.tokenBytes = bytes(internalToken);
        this.maxSkewMillis = maxSkewMillis;
        this.mayManage = mayManage;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (tokenBytes.length == 0) {
            return reject(response, 503, "服务未配置内部身份令牌");
        }
        String username = verify(request.getHeader(IDENTITY_HEADER));
        if (username == null || username.isBlank()) {
            return reject(response, 401, "缺少有效的网关身份签名");
        }
        request.setAttribute(USERNAME_ATTRIBUTE, username);
        if (isMutation(request.getMethod()) && !Boolean.TRUE.equals(mayManage.apply(username))) {
            return reject(response, 403, "没有管理权限");
        }
        return true;
    }

    private boolean mayManage(RestClient auth, String username) {
        try {
            Permission response = auth.post().uri("/internal/permissions/may-manage")
                    .body(Map.of("username", username)).retrieve().body(Permission.class);
            return response != null && response.allowed();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private String verify(String header) {
        if (header == null || header.isBlank()) return null;
        int dot = header.lastIndexOf('.');
        if (dot <= 0 || dot == header.length() - 1) return null;
        String encoded = header.substring(0, dot);
        if (!constantTimeEquals(hex(hmac(encoded)), header.substring(dot + 1))) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 3 || Math.abs(Clock.systemUTC().millis() - Long.parseLong(parts[2])) > maxSkewMillis) {
                return null;
            }
            if (!"-".equals(parts[0])) Long.parseLong(parts[0]);
            return parts[1];
        } catch (IllegalArgumentException malformed) {
            return null;
        }
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

    private static boolean reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"message\":\"" + message + "\"}");
        return false;
    }

    private static boolean isMutation(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    private static byte[] bytes(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
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

    private record Permission(boolean allowed, String reason) { }
}
