package com.aetheris.auth.service;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.entity.ResourceGrant;
import com.aetheris.auth.mapper.RbacRoleMapper;
import com.aetheris.auth.mapper.ResourceGrantMapper;
import com.aetheris.auth.mapper.UserGroupMapper;
import com.aetheris.auth.mapper.UserRoleMapper;
import com.aetheris.auth.model.DataScope;
import com.aetheris.auth.rbac.RbacConstants;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限决策引擎：auth-service 内唯一的权限判定实现。
 *
 * <p>判定输入只有"已认证的 username + 资源/动作 + 上下文"，与浏览器传来的任何
 * 请求头无关。判定规则：
 *
 * <ol>
 *   <li>账号不存在或禁用：一律拒绝。</li>
 *   <li>写类动作：仅由授权（用户/用户组/角色授权）或系统角色权限集合允许，
 *       admin 通过 sys_admin 角色持全量权限表达，viewer 无任何写权限。</li>
 *   <li>读动作：默认回退系统角色（admin/viewer 全读，兼容历史账号）；
 *      一旦存在带范围（resource_id 或 data_scope 非空）的读授权，
 *      读权限即按该授权收窄（"显式授权收窄默认"）。</li>
 *   <li>资源层级继承：domain 授权覆盖域内 table/model/dimension/metric，
 *      workspace 授权覆盖 dataset/dashboard/data_service。</li>
 * </ol>
 */
@Service
public class PermissionDecisionService {

    private final AuthUserService authUserService;
    private final ResourceGrantMapper grantMapper;
    private final UserGroupMapper userGroupMapper;
    private final UserRoleMapper userRoleMapper;
    private final RbacRoleMapper rbacRoleMapper;

    public PermissionDecisionService(
            AuthUserService authUserService,
            ResourceGrantMapper grantMapper,
            UserGroupMapper userGroupMapper,
            UserRoleMapper userRoleMapper,
            RbacRoleMapper rbacRoleMapper) {
        this.authUserService = authUserService;
        this.grantMapper = grantMapper;
        this.userGroupMapper = userGroupMapper;
        this.userRoleMapper = userRoleMapper;
        this.rbacRoleMapper = rbacRoleMapper;
    }

    /** 单个权限判定结果。 */
    public record Decision(boolean allowed, String reason) {
        public static final Decision ALLOWED = new Decision(true, null);

        public static Decision denied(String reason) {
            return new Decision(false, reason);
        }
    }

    /** 列表可见性：ALL=全部可见，IDS=仅限列出的 id，OPAQUE=需逐条判定（如表级授权）。 */
    public enum Mode { ALL, IDS, OPAQUE }

    public record Visibility(Mode mode, List<Long> ids) {
    }

    /**
     * 核心判定。
     *
     * @param ctx 数据范围上下文，键为 domainId/domainCode/tableId/fieldId/
     *            metricId/workspaceId/datasetId/dashboardId
     */
    public Decision check(String username, String resourceType, Long resourceId,
                          String action, Map<String, String> ctx) {
        AuthUser user = authUserService.findByUsername(username).orElse(null);
        if (user == null || !"active".equals(user.getStatus())) {
            return Decision.denied("账号不存在或已禁用");
        }

        boolean isRead = RbacConstants.isReadAction(action);
        List<ScopedGrant> grants = loadGrants(user.getId());

        if (grantsAllow(action, resourceType, resourceId, ctx, grants)) {
            return Decision.ALLOWED;
        }
        // 写动作永不自带默认放行；读动作在未被显式授权收窄时回退系统角色默认
        // （admin 全量、viewer 全读），收窄后只有匹配授权才能放行。
        boolean narrowed = isRead && isNarrowed(resourceType, grants);
        if (!narrowed && systemRoleAllows(user.getRole(), resourceType, action)) {
            return Decision.ALLOWED;
        }
        return Decision.denied("没有 " + resourceType + "." + action + " 权限");
    }

    /**
     * 计算某资源类型在某动作下的可见范围（用于列表过滤）。
     *
     * <p>只枚举可确定性收敛的授权：授权挂在资源链的"根"（domain 链的根是 domain，
     * dataset/dashboard/data_service 链的根是 workspace）时给出可选的根级 id 列表；
     * 子资源级（如 metric/table 限定 id）或非根级 data_scope 无法反推出根级 id
     * 集合时返回 OPAQUE，调用方逐条调用 /check 判定。
     */
    public Visibility visibility(String username, String resourceType, String action) {
        AuthUser user = authUserService.findByUsername(username).orElse(null);
        if (user == null || !"active".equals(user.getStatus())) {
            return new Visibility(Mode.IDS, List.of());
        }
        List<ScopedGrant> grants = loadGrants(user.getId());
        List<String> chain = RbacConstants.lineage(resourceType);
        String root = chain.get(chain.size() - 1);

        Set<Long> ids = new HashSet<>();
        boolean narrowed = false;
        for (ScopedGrant scoped : grants) {
            ResourceGrant grant = scoped.grant();
            if (!chain.contains(grant.getResourceType()) || !readRelevant(scoped, resourceType)) {
                continue;
            }
            if (grant.getResourceId() == null
                    && (grant.getDataScope() == null || grant.getDataScope().isEmpty())) {
                continue;
            }
            narrowed = true;
            if (!root.equals(grant.getResourceType())) {
                return new Visibility(Mode.OPAQUE, List.of());
            }
            if (grant.getResourceId() != null) {
                ids.add(grant.getResourceId());
            }
            DataScope scope = grant.getDataScope();
            if (scope != null && !scope.isEmpty()) {
                if (!root.equals(scope.getKind())) {
                    return new Visibility(Mode.OPAQUE, List.of());
                }
                if (scope.getValues() != null) {
                    scope.getValues().forEach(v -> addLong(ids, v));
                }
            }
        }

        if (!narrowed) {
            return new Visibility(Mode.ALL, List.of());
        }
        return new Visibility(Mode.IDS, new ArrayList<>(ids));
    }

    /**
     * 网关粗粒度写入口判定：service 为 gateway 路由前缀的粗粒度命名
     * （metadata/serving/workflow/quality/ingestion/governance/system）。
     */
    public Decision mayMutate(String username, String service) {
        AuthUser user = authUserService.findByUsername(username).orElse(null);
        if (user == null || !"active".equals(user.getStatus())) {
            return Decision.denied("账号不存在或已禁用");
        }
        if (hasCode(user, "service." + service + ".admin")) {
            return Decision.ALLOWED;
        }
        List<String> owned = RbacConstants.SERVICE_RESOURCES.getOrDefault(service, List.of());
        if (owned.isEmpty()) {
            return Decision.denied("没有 " + service + " 服务的写权限");
        }
        Set<String> writeActions = RbacConstants.WRITE_ACTIONS;
        for (ScopedGrant scoped : loadGrants(user.getId())) {
            ResourceGrant grant = scoped.grant();
            if (scoped.roleCodes() == null) {
                if (owned.contains(grant.getResourceType())
                        && grant.getActions() != null
                        && grant.getActions().stream().anyMatch(writeActions::contains)) {
                    return Decision.ALLOWED;
                }
            } else if (owned.contains(grant.getResourceType())) {
                for (String code : scoped.roleCodes()) {
                    int dot = code.lastIndexOf('.');
                    if (dot > 0
                            && owned.contains(code.substring(0, dot))
                            && writeActions.contains(code.substring(dot + 1))) {
                        return Decision.ALLOWED;
                    }
                }
            }
        }
        return Decision.denied("没有 " + service + " 服务的写权限");
    }

    /** 系统级管理入口（用户管理、审计日志、权限管理页面）判定。 */
    public Decision mayManage(String username) {
        AuthUser user = authUserService.findByUsername(username).orElse(null);
        if (user == null || !"active".equals(user.getStatus())) {
            return Decision.denied("账号不存在或已禁用");
        }
        return hasCode(user, RbacConstants.PERMISSION_SERVICE_ADMIN)
                ? Decision.ALLOWED
                : Decision.denied("没有系统管理权限");
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private static final Set<String> SYSTEM_ALLOWED_CODES = Set.of(
            "domain.view",
            "table.view", "table.query",
            "field.view", "field.query",
            "model.view", "dimension.view",
            "metric.view", "metric.query",
            "dataset.view", "dataset.query",
            "dashboard.view",
            "data_service.view", "data_service.execute",
            "datasource.view", "datasource.query"
    );

    /** 带角色权限码的授权：用户/用户组授权 roleCodes 为 null，角色授权携带其权限码。 */
    private record ScopedGrant(ResourceGrant grant, Set<String> roleCodes) {
    }

    /** 汇总某用户的全部启用授权：直接授权 + 所在组授权 + 所绑定角色的授权。 */
    private List<ScopedGrant> loadGrants(Long userId) {
        List<ScopedGrant> result = new ArrayList<>();
        for (ResourceGrant grant : grantMapper.selectEnabledFor("user", userId)) {
            result.add(new ScopedGrant(grant, null));
        }
        for (Long groupId : userGroupMapper.selectGroupIdsOfUser(userId)) {
            for (ResourceGrant grant : grantMapper.selectEnabledFor("group", groupId)) {
                result.add(new ScopedGrant(grant, null));
            }
        }
        for (Long roleId : userRoleMapper.selectRoleIdsOfUser(userId)) {
            Set<String> codes = new HashSet<>(rbacRoleMapper.selectPermissionCodes(roleId));
            for (ResourceGrant grant : grantMapper.selectEnabledFor("role", roleId)) {
                result.add(new ScopedGrant(grant, codes));
            }
        }
        return result;
    }

    private boolean grantsAllow(String action, String resourceType, Long resourceId,
                                Map<String, String> ctx, List<ScopedGrant> grants) {
        for (ScopedGrant scoped : grants) {
            if (grantAllows(scoped, action, resourceType, resourceId, ctx)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条授权是否放行 (resourceType, resourceId, action) @ ctx。
     * 角色授权以角色权限码为准（沿资源层级继承），用户/用户组授权以授权自身 actions 为准。
     */
    private boolean grantAllows(ScopedGrant scoped, String action, String resourceType,
                                Long resourceId, Map<String, String> ctx) {
        ResourceGrant grant = scoped.grant();
        if (!RbacConstants.lineage(resourceType).contains(grant.getResourceType())) {
            return false;
        }
        if (scoped.roleCodes() == null) {
            if (grant.getActions() == null || !grant.getActions().contains(action)) {
                return false;
            }
        } else if (!roleSupports(scoped.roleCodes(), resourceType, action)) {
            return false;
        }
        return scopeAllows(grant, resourceType, resourceId, ctx);
    }

    /** 授权范围（resource_id / data_scope）与请求目标是否匹配。 */
    private boolean scopeAllows(ResourceGrant grant, String resourceType,
                                Long resourceId, Map<String, String> ctx) {
        if (!grant.getResourceType().equals(resourceType)) {
            return ancestorScopeAllows(grant, ctx);
        }
        if (grant.getResourceId() != null && !grant.getResourceId().equals(resourceId)) {
            return false;
        }
        return grant.getDataScope() == null || scopeMatches(grant.getDataScope(), ctx);
    }

    private boolean ancestorScopeAllows(ResourceGrant grant, Map<String, String> ctx) {
        String contextKey = switch (grant.getResourceType()) {
            case "domain" -> "domainId";
            case "workspace" -> "workspaceId";
            case "table" -> "tableId";
            default -> null;
        };
        if (contextKey == null) {
            return false;
        }
        if (grant.getResourceId() != null) {
            String value = ctx == null ? null : ctx.get(contextKey);
            if (value == null || !String.valueOf(grant.getResourceId()).equals(value)) {
                return false;
            }
        }
        return grant.getDataScope() == null || scopeMatches(grant.getDataScope(), ctx);
    }

    /** data_scope 与上下文匹配：scope.kind 决定读取 ctx 的哪个键。 */
    private boolean scopeMatches(DataScope scope, Map<String, String> ctx) {
        if (scope == null) {
            return ctx == null;
        }
        if (scope.isEmpty()) {
            return true;
        }
        if (ctx == null) {
            return false;
        }
        String searched = switch (scope.getKind()) {
            case "domain" -> ctx.get("domainId");
            case "table" -> ctx.get("tableId");
            case "field" -> ctx.get("fieldId");
            case "metric" -> ctx.get("metricId");
            case "workspace" -> ctx.get("workspaceId");
            case "dataset" -> ctx.get("datasetId");
            case "dashboard" -> ctx.get("dashboardId");
            default -> null;
        };
        if (searched == null) {
            return false;
        }
        boolean matched = scope.getValues().contains(searched);
        // domain 同时允许按 code（如 electronic_commerce）匹配
        if (!matched && "domain".equals(scope.getKind())) {
            String domainCode = ctx.get("domainCode");
            matched = domainCode != null && scope.getValues().contains(domainCode);
        }
        return matched;
    }

    /** 该用户是否存在针对 resourceType（含祖先）的带范围读授权 → 读权限被收窄。 */
    private boolean isNarrowed(String resourceType, List<ScopedGrant> grants) {
        Set<String> chain = new HashSet<>(RbacConstants.lineage(resourceType));
        for (ScopedGrant scoped : grants) {
            ResourceGrant grant = scoped.grant();
            if (!chain.contains(grant.getResourceType()) || !readRelevant(scoped, resourceType)) {
                continue;
            }
            if (grant.getResourceId() != null
                    || (grant.getDataScope() != null && !grant.getDataScope().isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /** 角色权限沿资源层级是否支持某动作。 */
    private boolean roleSupports(Set<String> roleCodes, String resourceType, String action) {
        for (String rt : RbacConstants.lineage(resourceType)) {
            if (roleCodes.contains(rt + "." + action)) {
                return true;
            }
        }
        return false;
    }

    private boolean roleSupportsRead(Set<String> roleCodes, String resourceType) {
        return roleSupports(roleCodes, resourceType, "view")
                || roleSupports(roleCodes, resourceType, "query");
    }

    /** 授权是否携带目标资源类型的读动作：用户/用户组看 actions，角色看权限码。 */
    private boolean readRelevant(ScopedGrant scoped, String resourceType) {
        if (scoped.roleCodes() == null) {
            return scoped.grant().getActions() != null
                    && scoped.grant().getActions().stream()
                        .anyMatch(RbacConstants.READ_ACTIONS::contains);
        }
        return roleSupportsRead(scoped.roleCodes(), resourceType);
    }

    /** 系统角色默认权限：admin 全量；viewer 只读（与 V3 迁移中 sys_viewer 种子一致）。 */
    private boolean systemRoleAllows(String role, String resourceType, String action) {
        if ("admin".equals(role)) {
            return true;
        }
        return RbacConstants.isReadAction(action)
                && SYSTEM_ALLOWED_CODES.contains(resourceType + "." + action);
    }

    private boolean hasCode(AuthUser user, String code) {
        if ("admin".equals(user.getRole())) {
            return true;
        }
        for (Long roleId : userRoleMapper.selectRoleIdsOfUser(user.getId())) {
            if (rbacRoleMapper.selectPermissionCodes(roleId).contains(code)) {
                return true;
            }
        }
        return false;
    }

    private void addLong(Set<Long> target, String value) {
        try {
            target.add(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            // 非数字的 code 型范围值无法反推出 id 列表，忽略；此类情况会落入 OPAQUE 分支
        }
    }
}