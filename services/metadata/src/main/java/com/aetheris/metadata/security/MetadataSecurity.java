package com.aetheris.metadata.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * metadata 服务的统一权限门。
 *
 * <p>身份只来自网关签名（X-Aetheris-Identity 验签），浏览器可伪造的
 * X-User-Name / X-User-Role 不参与任何判定。每次判定都实时询问
 * auth-service 决策引擎，权限变更立即生效，不维护本地长缓存。
 *
 * <p>失败模式：身份缺失 401；判定拒绝 403（fail-closed，权限服务不可用
 * 一律拒绝）。
 */
@Component
public class MetadataSecurity {

    public static final String IDENTITY_ATTRIBUTE = "aetheris.identity";

    private final IdentityVerifier verifier;
    private final AuthPermissionClient permissionClient;

    public MetadataSecurity(IdentityVerifier verifier, AuthPermissionClient permissionClient) {
        this.verifier = verifier;
        this.permissionClient = permissionClient;
    }

    /** 从请求解析验签身份；无有效身份返回 null（由调用方决定 401 或拒绝）。 */
    public AetherisIdentity identity(HttpServletRequest request) {
        if (request == null) return null;
        Object cached = request.getAttribute(IDENTITY_ATTRIBUTE);
        if (cached instanceof AetherisIdentity cachedIdentity) return cachedIdentity;
        AetherisIdentity verified = verifier.verify(request);
        if (verified != null) request.setAttribute(IDENTITY_ATTRIBUTE, verified);
        return verified;
    }

    /** 解析身份，失败抛 401。 */
    public AetherisIdentity requireIdentity(HttpServletRequest request) {
        AetherisIdentity identity = identity(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            throw new UnauthorizedException("缺少有效的网关身份签名");
        }
        return identity;
    }

    /** 判定单条资源权限，失败抛 403；通过返回身份（含 username，供创建人等审计字段使用）。 */
    public AetherisIdentity require(HttpServletRequest request, String resourceType, Long resourceId,
                                    String action, Map<String, String> ctx) {
        AetherisIdentity identity = requireIdentity(request);
        AuthPermissionClient.Access access =
                permissionClient.check(identity.username(), resourceType, resourceId, action, ctx);
        if (!access.allowed()) {
            throw new ForbiddenException("没有 " + resourceType + "." + action + " 权限");
        }
        return identity;
    }

    /** 无异常的判定（用于列表场景的逐条过滤）。 */
    public boolean isAllowed(String username, String resourceType, Long resourceId,
                             String action, Map<String, String> ctx) {
        return permissionClient.check(username, resourceType, resourceId, action, ctx).allowed();
    }

    /** 请求某资源类型读列表时的可见范围（按业务域根资源收敛）。 */
    public Scope visibility(HttpServletRequest request, String resourceType, String action) {
        AetherisIdentity identity = requireIdentity(request);
        AuthPermissionClient.Visibility response =
                permissionClient.visibility(identity.username(), resourceType, action);
        return switch (response.mode()) {
            case "ALL" -> new Scope(ScopeMode.ALL, null);
            case "IDS" -> new Scope(ScopeMode.IDS, List.copyOf(response.ids()));
            default -> new Scope(ScopeMode.OPAQUE, null);
        };
    }

    public enum ScopeMode { ALL, IDS, OPAQUE }

    /** @param ids IDS 模式下的业务域 id 白名单；ALL/OPAQUE 时为空 */
    public record Scope(ScopeMode mode, List<Long> ids) {
    }

    /** 未认证（401）。 */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }

    /** 已认证但无权限（403）。 */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }
}