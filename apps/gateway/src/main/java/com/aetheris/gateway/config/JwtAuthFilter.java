package com.aetheris.gateway.config;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        HttpMethod method = exchange.getRequest().getMethod();

        if (method == HttpMethod.OPTIONS || isPublic(path)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return unauthorized(exchange);
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(authorization.substring(7))
                    .getPayload();
        } catch (Exception malformed) {
            return unauthorized(exchange);
        }

        String rawToken = authorization.substring(7);
        String jti = claims.getId();
        String revocationKey = jti == null ? TokenRevocationStore.fingerprint(rawToken) : jti;
        return revocationStore.isRevoked(revocationKey)
                .flatMap(revoked -> revoked
                        ? unauthorized(exchange)
                        : continueAuthenticated(exchange, chain, path, method, claims,
                                revocationKey))
                // Revocation state is an authentication boundary. If the shared
                // store is unavailable, accepting tokens would silently undo logout.
                .onErrorResume(error -> serviceUnavailable(exchange));
    }

    private Mono<Void> continueAuthenticated(
            ServerWebExchange exchange, GatewayFilterChain chain, String path,
            HttpMethod method, Claims claims, String revocationKey) {
        Mono<Void> revoke = "/api/auth/logout".equals(path)
                ? revocationStore.revoke(revocationKey, claims.getExpiration() == null
                        ? null : claims.getExpiration().toInstant())
                : Mono.empty();

        String username = claims.getSubject();
        String role = claims.get("role", String.class);
        String authenticatedRole = role == null ? "viewer" : role;
        Long uid = claims.get("uid", Long.class);

        exchange.getAttributes().put(AUTHENTICATED_USER_ATTRIBUTE, username);
        exchange.getAttributes().put(AUTHENTICATED_ROLE_ATTRIBUTE, authenticatedRole);
        exchange.getAttributes().put(AUTHENTICATED_UID_ATTRIBUTE, uid);

        String identitySignature = identitySigner.sign(uid, username);
        ServerHttpRequest mutated = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.set("X-User-Name", username);
                    headers.set("X-User-Role", authenticatedRole);
                    if (uid != null) {
                        headers.set("X-User-Id", String.valueOf(uid));
                    }
                    if (identitySignature != null) {
                        headers.set(IdentitySigner.IDENTITY_HEADER, identitySignature);
                    }
                })
                .build();
        ServerWebExchange authenticated = exchange.mutate().request(mutated).build();

        return revoke.then(Mono.defer(() -> {
            String service = serviceOf(path, method);
            if (service != null) {
                return permissionGate.mayMutate(username, service)
                    .flatMap(allowed -> allowed
                            ? chain.filter(authenticated)
                            : forbidden(exchange))
                    .onErrorResume(error -> serviceUnavailable(exchange));
            }
            if (isSensitive(path)) {
                return permissionGate.mayManage(username)
                    .flatMap(allowed -> allowed
                            ? chain.filter(authenticated)
                            : forbidden(exchange))
                    .onErrorResume(error -> serviceUnavailable(exchange));
            }
            return chain.filter(authenticated);
        }));
    }

    private boolean isPublic(String path) {
        return PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean isSensitive(String path) {
        return SENSITIVE_PREFIXES.stream().anyMatch(path::startsWith);
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
