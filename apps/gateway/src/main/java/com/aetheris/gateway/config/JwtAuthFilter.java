package com.aetheris.gateway.config;

import com.aetheris.security.protocol.BrowserSessionCookies;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 网关认证与粗粒度入口保护。
 *
 * <ol>
 *   <li>JWT 验签（auth-service 是唯一签发方）：解析 uid、username、role。</li>
 *   <li>伪造防护：X-User-Name / X-User-Role / X-User-Id 请求头全部以令牌
 *       内容覆盖重写；另附 X-Aetheris-Identity 共享令牌签名，业务服务
 *       据此识别"经过网关认证"的身份。</li>
 *   <li>粗粒度入口：敏感管理前缀（/api/auth/users、/api/auth/rbac、
 *       /api/system/audit-logs）走 may-manage；管理类服务写请求走
 *       may-mutate 问询 auth 决策引擎。判定失败 403，权限服务不可用
 *       fail-closed 503。业务服务仍必须做资源级权限判断，这里只是
 *       入口保护。</li>
 * </ol>
 */
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    /** 已认证身份，供审计等更早的过滤器读取；请求头可被客户端伪造，不能作为审计来源。 */
    public static final String AUTHENTICATED_USER_ATTRIBUTE = "aetheris.auth.username";
    public static final String AUTHENTICATED_ROLE_ATTRIBUTE = "aetheris.auth.role";
    public static final String AUTHENTICATED_UID_ATTRIBUTE = "aetheris.auth.uid";

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/actuator/health",
            "/actuator/info"
    );

    /** 系统管理入口：全部方法都要求系统管理权限（不再信任 role 字符串）。 */
    private static final Set<String> SENSITIVE_PREFIXES = Set.of(
            "/api/auth/users",
            "/api/auth/rbac",
            "/api/system/audit-logs",
            "/api/system/permission-events"
    );

    /** 管理类服务的写请求在网关做粗粒度入口问询。serving/analytics 的
     * 查询执行不属于管理写，放到业务服务资源级判断。 */
    private static final Map<String, String> SERVICE_BY_PREFIX = Map.of(
            "/api/system/", "system",
            "/api/metadata/", "metadata",
            "/api/quality/", "quality",
            "/api/ingestion/", "ingestion",
            "/api/governance/", "governance",
            "/api/workflow/", "workflow"
    );

    // These POSTs execute bounded reads; metadata still verifies the signed
    // identity and the concrete datasource's query permission. Unsaved tests,
    // writes and similarly named routes remain behind the mutation gate.
    private static final Pattern DATASOURCE_READ_EXECUTION = Pattern.compile(
            "^/api/metadata/datasources/[0-9]+/(query|test|preview)$");
    private static final Pattern PUBLISHED_API_QUERY = Pattern.compile(
            "^/api/serving/v1/apis/[A-Za-z][A-Za-z0-9_]{1,63}/query$");

    private final SecretKey key;
    private final TokenRevocationStore revocationStore;
    private final PermissionGate permissionGate;
    private final IdentitySigner identitySigner;

    @Autowired
    public JwtAuthFilter(
            @Value("${jwt.secret}") String secret,
            TokenRevocationStore revocationStore,
            PermissionGate permissionGate,
            IdentitySigner identitySigner) {
        this(parseKey(secret), revocationStore, permissionGate, identitySigner);
    }

    /** 测试构造：验签密钥、撤销存储与判定门全部显式注入。 */
    public JwtAuthFilter(SecretKey key, TokenRevocationStore revocationStore,
                         PermissionGate permissionGate, IdentitySigner identitySigner) {
        this.key = key;
        this.revocationStore = revocationStore;
        this.permissionGate = permissionGate;
        this.identitySigner = identitySigner;
    }

    /** 包级可见：验签密钥派生逻辑（供同包测试验证短密钥拒绝）。 */
    static SecretKey parseKey(String secret) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32 || "change-me-to-a-long-random-secret".equals(secret)) {
            throw new IllegalStateException("JWT_SECRET must be a unique random value of at least 32 UTF-8 bytes");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate().headers(headers -> {
            for (String header : List.of("X-Aetheris-Identity", "X-User-Name", "X-User-Id", "X-User-Role",
                    "X-Internal-Token", "X-Aetheris-Client-IP", "X-Aetheris-User-Proof",
                    "X-Aetheris-Workload-Proof", "X-Aetheris-Response-Proof")) headers.remove(header);
        }).build();
        return filterSanitized(exchange.mutate().request(sanitized).build(), next -> {
            if (next.getRequest().getURI().getPath().startsWith("/api/auth/")) return chain.filter(next);
            ServerHttpRequest noCredentials = next.getRequest().mutate().headers(headers -> {
                headers.remove(HttpHeaders.AUTHORIZATION); headers.remove(HttpHeaders.COOKIE);
            }).build();
            var request = new org.springframework.http.server.reactive.ServerHttpRequestDecorator(noCredentials) {
                @Override public org.springframework.util.MultiValueMap<String, org.springframework.http.HttpCookie> getCookies() {
                    return new org.springframework.util.LinkedMultiValueMap<>();
                }
            };
            return chain.filter(next.mutate().request(request).build());
        });
    }

    private Mono<Void> filterSanitized(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();
        String rawPath = exchange.getRequest().getURI().getRawPath().toLowerCase(java.util.Locale.ROOT);
        if (path.contains(";") || path.contains("\\") || path.contains("//")
                || java.util.Arrays.stream(path.split("/", -1)).anyMatch(segment -> segment.equals(".") || segment.equals(".."))
                || rawPath.matches(".*%(?:2f|5c|2e|3b|25|00|0a|0d).*")) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        }

        // Serving authenticates and authorizes this one published-query contract.
        // Strip every caller supplied service identity before delegating to it.
        String apiKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (method == HttpMethod.POST && PUBLISHED_API_QUERY.matcher(path).matches()
                && apiKey != null && !apiKey.isBlank()) {
            ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> {
                for (String header : List.of("X-Aetheris-Identity", "X-User-Name", "X-User-Id",
                        "X-User-Role", "X-Internal-Token", "X-Aetheris-Client-IP")) headers.remove(header);
            }).build();
            return chain.filter(exchange.mutate().request(request).build());
        }

        if (method == HttpMethod.OPTIONS || isPublic(path)) {
            if ("/api/auth/login".equals(path) || "/api/auth/login/".equals(path)) {
                java.net.InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                String source = remote == null ? "unknown"
                        : remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
                ServerHttpRequest request = exchange.getRequest().mutate()
                        .headers(headers -> headers.set("X-Aetheris-Client-IP", source)).build();
                return chain.filter(exchange.mutate().request(request).build());
            }
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean bearer = authorization != null && authorization.startsWith("Bearer ");
        String selected = uniqueCookie(exchange, BrowserSessionCookies.SELECTOR);
        String rawToken = bearer
                ? authorization.substring(7)
                : BrowserSessionCookies.validSid(selected)
                        ? uniqueCookie(exchange, BrowserSessionCookies.name(BrowserSessionCookies.ACCESS, selected)) : null;
        if (method == HttpMethod.POST && "/api/auth/logout".equals(path)) {
            return forwardLogout(exchange, chain, rawToken);
        }
        if (rawToken == null || rawToken.isBlank()) {
            // Logout is idempotent and must clear cookies even when the access
            // token already expired. If a valid access cookie exists, the path
            // continues through the normal revocation branch below.
            if ("/api/auth/logout".equals(path)) {
                return chain.filter(exchange);
            }
            return unauthorized(exchange);
        }

        String username;
        Long uid;
        String authenticatedRole;
        String jti;
        Instant expiresAt;
        long accessVersion;
        String sid;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .require("typ", "access")
                    .build()
                    .parseSignedClaims(rawToken)
                    .getPayload();
            // Validate claim types inside the authentication boundary as well:
            // a signed token with malformed claims must produce 401, not 500.
            username = claims.getSubject();
            uid = claims.get("uid", Long.class);
            String role = claims.get("role", String.class);
            authenticatedRole = role == null ? "viewer" : role;
            jti = claims.getId();
            expiresAt = claims.getExpiration() == null ? null : claims.getExpiration().toInstant();
            Long versionClaim = claims.get("av", Long.class);
            accessVersion = versionClaim == null ? -1L : versionClaim;
            sid = claims.get("sid", String.class);
            if (accessVersion < 0 || sid == null || !UUID.fromString(sid).toString().equals(sid))
                throw new IllegalArgumentException("Browser family required");
            if (!bearer && !sid.equals(selected)) return unauthorized(exchange);
            if (username == null || username.isBlank() || uid == null || uid <= 0 || expiresAt == null) {
                return unauthorized(exchange);
            }
        } catch (Exception malformed) {
            // Logout remains idempotent when the short-lived access cookie has
            // expired or is malformed: auth-service must still clear and
            // revoke the family using the separate logout capability.
            return "/api/auth/logout".equals(path)
                    ? chain.filter(exchange)
                    : unauthorized(exchange);
        }

        String revocationKey = jti == null ? TokenRevocationStore.fingerprint(rawToken) : jti;
        return revocationStore.isRevoked(revocationKey)
                .flatMap(revoked -> revoked
                        ? "/api/auth/logout".equals(path) ? chain.filter(exchange) : unauthorized(exchange)
                        : permissionGate.browserSessionActive(uid, username, accessVersion, sid).defaultIfEmpty(false)
                            .flatMap(active -> active
                                ? continueAuthenticated(exchange, chain, path, method,
                                    username, uid, authenticatedRole, accessVersion, expiresAt, revocationKey)
                                : "/api/auth/logout".equals(path) ? chain.filter(exchange) : unauthorized(exchange)))
                // Revocation state is an authentication boundary. If the shared
                // store is unavailable, accepting tokens would silently undo logout.
                .onErrorResume(error -> serviceUnavailable(exchange));
    }

    private Mono<Void> continueAuthenticated(
            ServerWebExchange exchange, GatewayFilterChain chain, String path,
            HttpMethod method, String username, Long uid, String authenticatedRole,
            long accessVersion, Instant expiresAt, String revocationKey) {
        exchange.getAttributes().put(AUTHENTICATED_USER_ATTRIBUTE, username);
        exchange.getAttributes().put(AUTHENTICATED_ROLE_ATTRIBUTE, authenticatedRole);
        exchange.getAttributes().put(AUTHENTICATED_UID_ATTRIBUTE, uid);

        String requestId = exchange.getRequest().getHeaders().getFirst("X-Request-Id");
        if (requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            requestId = UUID.randomUUID().toString();
        }
        exchange.getResponse().getHeaders().set("X-Request-Id", requestId);
        final String propagatedRequestId = requestId;
        String audience = audienceOf(path);
        if (audience == null) { exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND); return exchange.getResponse().setComplete(); }
        String identitySignature = identitySigner.sign(uid, username, accessVersion, audience, requestId, expiresAt);
        String gateProof = identitySigner.sign(uid, username, accessVersion, "gateway", requestId, expiresAt);
        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers -> {
            headers.set(IdentitySigner.IDENTITY_HEADER, identitySignature);
            headers.set("X-Request-Id", propagatedRequestId);
        }).build();
        ServerWebExchange authenticated = exchange.mutate().request(mutated).build();

        return Mono.defer(() -> {
            String service = serviceOf(path, method);
            if (isSensitive(path)) {
                return permissionGate.mayManage(gateProof)
                    .flatMap(allowed -> allowed
                            ? chain.filter(authenticated)
                            : forbidden(exchange))
                    .onErrorResume(error -> serviceUnavailable(exchange));
            }
            if (service != null) {
                return permissionGate.mayMutate(gateProof, service)
                    .flatMap(allowed -> allowed
                            ? chain.filter(authenticated)
                            : forbidden(exchange))
                    .onErrorResume(error -> serviceUnavailable(exchange));
            }
            return chain.filter(authenticated);
        });
    }

    private Mono<Void> forwardLogout(ServerWebExchange exchange, GatewayFilterChain chain, String access) {
        // Auth validates the independent logout capability and persists family revocation.
        // Redis is an additional jti blacklist, not a prerequisite for this privilege-reducing route.
        Mono<Void> blacklist = Mono.empty();
        String selected = uniqueCookie(exchange, BrowserSessionCookies.SELECTOR);
        String logoutCookie = BrowserSessionCookies.validSid(selected)
                ? uniqueCookie(exchange, BrowserSessionCookies.name(BrowserSessionCookies.LOGOUT, selected)) : null;
        if (access != null && !access.isBlank() && logoutCookie != null) {
            try {
                var claims = Jwts.parser().verifyWith(key).require("typ", "access").build().parseSignedClaims(access).getPayload();
                var logout = Jwts.parser().verifyWith(key).require("typ", "browser-logout").build()
                        .parseSignedClaims(logoutCookie).getPayload();
                String sid = claims.get("sid", String.class);
                Long uid = claims.get("uid", Long.class), av = claims.get("av", Long.class);
                // An old family capability must not revoke the access jti of a newer login.
                if (claims.getExpiration() != null && sid != null && sid.equals(selected) && sid.equals(logout.get("sid", String.class))
                        && uid != null && uid > 0 && uid.equals(logout.get("uid", Long.class))
                        && av != null && av >= 0 && av.equals(logout.get("av", Long.class))
                        && claims.getSubject() != null && claims.getSubject().equals(logout.getSubject())) {
                    String jti = claims.getId() == null ? TokenRevocationStore.fingerprint(access) : claims.getId();
                    blacklist = Mono.defer(() -> revocationStore.revoke(jti, claims.getExpiration().toInstant()))
                            .timeout(java.time.Duration.ofSeconds(1)).onErrorResume(unavailable -> Mono.empty());
                }
            } catch (Exception expiredOrMalformed) { /* The logout proof has its own signature and lifetime. */ }
        }
        return blacklist.then(Mono.defer(() -> chain.filter(exchange)));
    }

    private static String uniqueCookie(ServerWebExchange exchange, String name) {
        var values = exchange.getRequest().getCookies().get(name);
        return values != null && values.size() == 1 ? values.getFirst().getValue() : null;
    }

    static String audienceOf(String path) {
        for (String service : List.of("auth", "system", "metadata", "quality", "ingestion", "governance", "workflow", "serving"))
            if (path.startsWith("/api/" + service + "/")) return service;
        if (path.startsWith("/api/analytics/")) return "serving";
        if (path.startsWith("/api/ai/")) return "ai-agent";
        return null;
    }

    private boolean isPublic(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix)
                || path.equals(prefix + "/"));
    }

    private boolean isSensitive(String path) {
        return SENSITIVE_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix)
                || path.startsWith(prefix + "/"));
    }

    /** 管理类服务的写请求 → 粗粒度判定；读请求与其它前缀返回 null（不拦）。 */
    private String serviceOf(String path, HttpMethod method) {
        if (method == HttpMethod.POST && ("/api/metadata/query".equals(path)
                || DATASOURCE_READ_EXECUTION.matcher(path).matches())) {
            return null;
        }
        if (method != HttpMethod.POST && method != HttpMethod.PUT
                && method != HttpMethod.PATCH && method != HttpMethod.DELETE) {
            return null;
        }
        if ((method==HttpMethod.POST&&path.equals("/api/metadata/api-assets"))
                || ((method==HttpMethod.PUT||method==HttpMethod.DELETE)&&path.matches("/api/metadata/api-assets/[0-9]+"))
                || (method==HttpMethod.POST&&path.matches("/api/metadata/api-assets/[0-9]+/(publish|deprecate)"))) {
            return "serving";
        }
        return SERVICE_BY_PREFIX.entrySet().stream()
                .filter(entry -> path.startsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> forbidden(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return exchange.getResponse().setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
