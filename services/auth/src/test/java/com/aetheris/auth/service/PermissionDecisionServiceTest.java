package com.aetheris.auth.service;

import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.entity.ResourceGrant;
import com.aetheris.auth.mapper.RbacRoleMapper;
import com.aetheris.auth.mapper.ResourceGrantMapper;
import com.aetheris.auth.mapper.UserGroupMapper;
import com.aetheris.auth.mapper.UserRoleMapper;
import com.aetheris.auth.model.DataScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionDecisionServiceTest {

    private AuthUserService authUserService;
    private ResourceGrantMapper grantMapper;
    private UserGroupMapper userGroupMapper;
    private UserRoleMapper userRoleMapper;
    private PermissionDecisionService service;

    /** user 主体的直接授权列表：测试通过修改该列表模拟授权/撤销（决策引擎每次实时读取）。 */
    private final List<ResourceGrant> userGrants = new ArrayList<>();
    private AuthUser user;

    @BeforeEach
    void setUp() {
        authUserService = mock(AuthUserService.class);
        grantMapper = mock(ResourceGrantMapper.class);
        userGroupMapper = mock(UserGroupMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        RbacRoleMapper rbacRoleMapper = mock(RbacRoleMapper.class);
        service = new PermissionDecisionService(
                authUserService, grantMapper, userGroupMapper, userRoleMapper, rbacRoleMapper);

        user = user(1L, "alice", "viewer");
        when(authUserService.findByUsername("alice")).thenReturn(Optional.of(user));
        when(grantMapper.selectEnabledFor(eq("user"), anyLong())).thenReturn(userGrants);
        when(grantMapper.selectEnabledFor(eq("group"), anyLong())).thenReturn(List.of());
        when(grantMapper.selectEnabledFor(eq("role"), anyLong())).thenReturn(List.of());
        when(userGroupMapper.selectGroupIdsOfUser(anyLong())).thenReturn(List.of());
        when(userRoleMapper.selectRoleIdsOfUser(anyLong())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // 阶段二 A 必测场景
    // ------------------------------------------------------------------

    @Test
    @DisplayName("viewer 默认不能写入：无任何授权时 create 被拒")
    void viewerWriteDeniedByDefault() {
        PermissionDecisionService.Decision decision =
                service.check("alice", "metric", 1L, "create", Map.of("domainId", "5"));
        assertFalse(decision.allowed());
    }

    @Test
    @DisplayName("admin 兼容：无显式授权也拥有全量权限（经由系统角色表达）")
    void adminCompatKeepsFullAccess() {
        user.setRole("admin");
        assertTrue(service.check("alice", "metric", 1L, "create", Map.of()).allowed());
        assertTrue(service.check("alice", "user", null, "admin", Map.of()).allowed());
    }

    @Test
    @DisplayName("viewer 兼容：无收窄授权时保留全读回退")
    void viewerCompatKeepsReadFallback() {
        assertTrue(service.check("alice", "metric", 1L, "view", Map.of("domainId", "5")).allowed());
        assertTrue(service.check("alice", "dataset", 9L, "query", Map.of()).allowed());
    }

    @Test
    @DisplayName("无业务域权限时不能读取其中的表和指标（domain 收窄）")
    void domainNarrowingDeniesOtherDomains() {
        userGrants.add(grant("domain", 5L, null, "view", "query"));

        assertTrue(service.check("alice", "table", 12L, "view",
                Map.of("domainId", "5", "tableId", "12")).allowed());
        assertFalse(service.check("alice", "table", 21L, "view",
                Map.of("domainId", "6", "tableId", "21")).allowed());
        assertFalse(service.check("alice", "metric", 3L, "query",
                Map.of("domainId", "6", "metricId", "3")).allowed());
    }

    @Test
    @DisplayName("data_scope 支持按业务域 code 匹配（domain 收窄按 code）")
    void domainScopeMatchesByCode() {
        userGrants.add(grant("domain", null, new DataScope("domain", List.of("electronic_commerce")), "view"));
        assertTrue(service.check("alice", "metric", 3L, "view",
                Map.of("domainId", "5", "domainCode", "electronic_commerce")).allowed());
        assertFalse(service.check("alice", "metric", 4L, "view",
                Map.of("domainId", "5", "domainCode", "logistics")).allowed());
    }

    @Test
    @DisplayName("有表权限但无字段权限：字段被显式字段授权收窄后拒绝")
    void fieldNarrowingDeniesUngrantedFields() {
        userGrants.add(grant("table", 12L, null, "view", "query"));
        userGrants.add(grant("field", 3L, null, "view"));

        assertTrue(service.check("alice", "table", 12L, "view",
                Map.of("domainId", "5", "tableId", "12")).allowed());
        assertTrue(service.check("alice", "field", 3L, "view",
                Map.of("tableId", "12", "fieldId", "3")).allowed());
        assertFalse(service.check("alice", "field", 4L, "view",
                Map.of("tableId", "12", "fieldId", "4")).allowed());
    }

    @Test
    @DisplayName("撤销授权后旧状态不会继续放行（决策引擎每次实时读取，无陈旧缓存）")
    void revokedGrantTakesEffectImmediately() {
        userGrants.add(grant("metric", null, null, "create"));
        assertTrue(service.check("alice", "metric", 1L, "create", Map.of()).allowed());

        userGrants.clear();
        assertFalse(service.check("alice", "metric", 1L, "create", Map.of()).allowed());
    }

    @Test
    @DisplayName("禁用账号一律拒绝，读动作也不例外")
    void disabledUserAlwaysDenied() {
        user.setStatus("disabled");
        assertFalse(service.check("alice", "metric", 1L, "view", Map.of()).allowed());
        assertFalse(service.check("alice", "metric", 1L, "create", Map.of()).allowed());
    }

    @Test
    @DisplayName("不存在的账号一律拒绝（越权尝试）")
    void unknownUserDenied() {
        assertFalse(service.check("ghost", "metric", 1L, "view", Map.of()).allowed());
        assertFalse(service.mayMutate("ghost", "metadata").allowed());
    }

    // ------------------------------------------------------------------
    // 可见性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("无收窄授权时可见性为 ALL")
    void visibilityAllWhenNoNarrowing() {
        PermissionDecisionService.Visibility visibility =
                service.visibility("alice", "metric", "view");
        assertEquals(PermissionDecisionService.Mode.ALL, visibility.mode());
    }

    @Test
    @DisplayName("domain 级读授权收敛为 IDS 集合（resource_id 与 data_scope 合并）")
    void visibilityIdsWhenDomainScoped() {
        userGrants.add(grant("domain", 5L, null, "view"));
        userGrants.add(grant("domain", null, new DataScope("domain", List.of("6", "7")), "view", "query"));

        PermissionDecisionService.Visibility visibility =
                service.visibility("alice", "metric", "view");
        assertEquals(PermissionDecisionService.Mode.IDS, visibility.mode());
        assertEquals(Set.of(5L, 6L, 7L), Set.copyOf(visibility.ids()));
    }

    @Test
    @DisplayName("子资源级（metric 单条）授权无法反推根级 id 集合，返回 OPAQUE")
    void visibilityOpaqueWhenSubResourceScoped() {
        userGrants.add(grant("metric", 7L, null, "view"));
        assertEquals(PermissionDecisionService.Mode.OPAQUE,
                service.visibility("alice", "metric", "view").mode());
    }

    @Test
    @DisplayName("gateway 粗粒度写入口：viewer 无写授权被拒，授予后放行")
    void mayMutateRequiresGrantForViewer() {
        assertFalse(service.mayMutate("alice", "metadata").allowed());
        userGrants.add(grant("metric", null, null, "update"));
        assertTrue(service.mayMutate("alice", "metadata").allowed());
    }

    @Test
    @DisplayName("系统级管理入口：viewer 被拒，admin 放行")
    void mayManageAdminOnly() {
        assertFalse(service.mayManage("alice").allowed());
        user.setRole("admin");
        assertTrue(service.mayManage("alice").allowed());
    }

    // ------------------------------------------------------------------
    // 测试工具
    // ------------------------------------------------------------------

    private AuthUser user(Long id, String username, String role) {
        AuthUser authUser = new AuthUser();
        authUser.setId(id);
        authUser.setUsername(username);
        authUser.setRole(role);
        authUser.setStatus("active");
        return authUser;
    }

    private ResourceGrant grant(String resourceType, Long resourceId,
                                DataScope scope, String... actions) {
        ResourceGrant grant = new ResourceGrant();
        grant.setGranteeType("user");
        grant.setGranteeId(1L);
        grant.setResourceType(resourceType);
        grant.setResourceId(resourceId);
        grant.setDataScope(scope);
        grant.setActions(List.of(actions));
        grant.setEnabled(true);
        return grant;
    }
}