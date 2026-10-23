package com.aetheris.metadata.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * metadata 服务的统一权限门。
 *
 * <p>身份只来自已验签并实时检查的TrustedUser，浏览器可伪造的
 * X-User-Name / X-User-Role 不参与任何判定。每次判定都实时询问
 * auth-service 决策引擎，权限变更立即生效，不维护本地长缓存。
 *
 * <p>失败模式：身份缺失 401；判定拒绝 403（fail-closed，权限服务不可用
 * 一律拒绝）。
 */
@Component
public class MetadataSecurity {

    public static final String IDENTITY_ATTRIBUTE = "aetheris.identity";

    private final AuthPermissionClient permissionClient;

    public MetadataSecurity(AuthPermissionClient permissionClient) {
        this.permissionClient = permissionClient;
    }

    /** 从请求解析验签身份；无有效身份返回 null（由调用方决定 401 或拒绝）。 */
    public AetherisIdentity identity(HttpServletRequest request) {
        if (request == null) return null;
        if (!(request.getAttribute(com.aetheris.security.servlet.TrustedUser.ATTRIBUTE)
                instanceof com.aetheris.security.servlet.TrustedUser user)) return null;
        return new AetherisIdentity(user.proof().uid(), user.proof().username());
    }

    /** 解析身份，失败抛 401。 */
    public AetherisIdentity requireIdentity(HttpServletRequest request) {
        AetherisIdentity identity = identity(request);
        if (identity == null || identity.username() == null || identity.username().isBlank()) {
            throw new UnauthorizedException("缺少有效的用户证明");
        }
        return identity;
    }

    /** 判定单条资源权限，失败抛 403；通过返回身份（含 username，供创建人等审计字段使用）。 */
    public AetherisIdentity require(HttpServletRequest request, String resourceType, Long resourceId,
                                    String action, Map<String, String> ctx) {
        AetherisIdentity identity = requireIdentity(request);
        AuthPermissionClient.Access access =
                permissionClient.check(identity.username(), resourceType, resourceId, action, ctx);
        if (!access.available()) {
            throw new PermissionServiceUnavailableException("权限服务暂时不可用，请稍后重试");
        }
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

    /** A signed, live auth decision is required even for administrators; request role headers have no meaning. */
    public boolean canReadGlobalQueryAudit(HttpServletRequest request) {
        var identity = requireIdentity(request);
        var access = permissionClient.check(identity.username(), "query_audit", null, "view", Map.of());
        if (!access.available()) {
            throw new PermissionServiceUnavailableException("权限服务暂时不可用，请稍后重试");
        }
        return access.allowed();
    }

    public java.util.Set<Long> allowedIds(String username, String resourceType, String action,
                                          Map<Long, Map<String, String>> resourceContexts) {
        return permissionClient.checkBatch(username, resourceType, action, resourceContexts);
    }

    public java.util.Set<Long> allowedIdsStrict(String username,String resourceType,String action,Map<Long,Map<String,String>> contexts) {
        return permissionClient.checkBatchStrict(username,resourceType,action,contexts);
    }

    public Scope visibilityStrict(HttpServletRequest request,String resourceType,String action) {
        var response=permissionClient.visibilityStrict(requireIdentity(request).username(),resourceType,action);
        return switch(response.mode()) {
            case "ALL" -> new Scope(ScopeMode.ALL,null);
            case "IDS" -> new Scope(ScopeMode.IDS,List.copyOf(response.ids()));
            case "OPAQUE" -> new Scope(ScopeMode.OPAQUE,null);
            default -> throw new PermissionServiceUnavailableException("权限服务暂时不可用，请稍后重试");
        };
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

    /** 写入口及 strict 列表不能把鉴权依赖故障伪装成无权限或成功空集。 */
    public static class PermissionServiceUnavailableException extends RuntimeException {
        public PermissionServiceUnavailableException(String message) { super(message); }
    }
}
