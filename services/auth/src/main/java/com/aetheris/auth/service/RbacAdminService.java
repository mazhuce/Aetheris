package com.aetheris.auth.service;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.dto.GrantRequest;
import com.aetheris.auth.dto.GrantUpdateRequest;
import com.aetheris.auth.dto.GrantView;
import com.aetheris.auth.dto.GroupMemberView;
import com.aetheris.auth.dto.OrganizationRequest;
import com.aetheris.auth.dto.RbacRoleRequest;
import com.aetheris.auth.dto.UserGroupRequest;
import com.aetheris.auth.dto.WorkspaceRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.entity.Organization;
import com.aetheris.auth.entity.RbacPermission;
import com.aetheris.auth.entity.RbacRole;
import com.aetheris.auth.entity.ResourceGrant;
import com.aetheris.auth.entity.UserGroup;
import com.aetheris.auth.entity.Workspace;
import com.aetheris.auth.mapper.OrganizationMapper;
import com.aetheris.auth.mapper.RbacPermissionMapper;
import com.aetheris.auth.mapper.RbacRoleMapper;
import com.aetheris.auth.mapper.ResourceGrantMapper;
import com.aetheris.auth.mapper.UserGroupMapper;
import com.aetheris.auth.mapper.UserRoleMapper;
import com.aetheris.auth.mapper.WorkspaceMapper;
import com.aetheris.auth.model.DataScope;
import com.aetheris.auth.web.ConflictException;
import com.aetheris.auth.web.InvalidRequestException;
import com.aetheris.auth.web.NotFoundException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限管理入口：角色/权限目录/用户组/组织空间/用户角色绑定/资源授权的
 * 创建、修改与撤销。
 *
 * <p>所有写操作：先校验 → 持久化 → 递增权限版本 → 写入权限审计事件。
 * 任何影响决策引擎输入的变更（授权、角色、角色权限、组、组成员、用户角色）
 * 都会 bump 权限版本，触发下游缓存失效。
 */
@Service
public class RbacAdminService {

    private static final Set<String> DATA_SCOPE_KINDS = Set.of(
            "workspace", "domain", "table", "field", "metric", "dataset", "dashboard");
    private static final Set<String> GRANTEE_TYPES = Set.of("user", "group", "role");
    private static final Set<String> SYSTEM_ROLE_CODES = Set.of("sys_admin", "sys_viewer");

    private final AuthUserService authUserService;
    private final RbacRoleMapper rbacRoleMapper;
    private final RbacPermissionMapper rbacPermissionMapper;
    private final UserGroupMapper userGroupMapper;
    private final UserRoleMapper userRoleMapper;
    private final OrganizationMapper organizationMapper;
    private final WorkspaceMapper workspaceMapper;
    private final ResourceGrantMapper resourceGrantMapper;
    private final PermissionVersionService permissionVersionService;
    private final PermissionAuditClient auditClient;

    public RbacAdminService(
            AuthUserService authUserService,
            RbacRoleMapper rbacRoleMapper,
            RbacPermissionMapper rbacPermissionMapper,
            UserGroupMapper userGroupMapper,
            UserRoleMapper userRoleMapper,
            OrganizationMapper organizationMapper,
            WorkspaceMapper workspaceMapper,
            ResourceGrantMapper resourceGrantMapper,
            PermissionVersionService permissionVersionService,
            PermissionAuditClient auditClient) {
        this.authUserService = authUserService;
        this.rbacRoleMapper = rbacRoleMapper;
        this.rbacPermissionMapper = rbacPermissionMapper;
        this.userGroupMapper = userGroupMapper;
        this.userRoleMapper = userRoleMapper;
        this.organizationMapper = organizationMapper;
        this.workspaceMapper = workspaceMapper;
        this.resourceGrantMapper = resourceGrantMapper;
        this.permissionVersionService = permissionVersionService;
        this.auditClient = auditClient;
    }

    // ------------------------------------------------------------------
    // 角色与权限目录
    // ------------------------------------------------------------------

    public List<RbacRole> listRoles() {
        return rbacRoleMapper.selectList(
                new LambdaQueryWrapper<RbacRole>().orderByAsc(RbacRole::getId));
    }

    public RbacRole createRole(String operator, RbacRoleRequest request) {
        ensureRoleCodeFree(request.code(), null);
        RbacRole role = new RbacRole();
        role.setCode(request.code());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setIsSystem(false);
        rbacRoleMapper.insert(role);
        afterChange(operator, "role", role.getId(), "create", "GRANTED");
        return role;
    }

    public RbacRole updateRole(String operator, Long id, RbacRoleRequest request) {
        RbacRole role = requireRole(id);
        requireNotSystem(role);
        ensureRoleCodeFree(request.code(), id);
        role.setCode(request.code());
        role.setName(request.name());
        role.setDescription(request.description());
        role.setUpdatedAt(OffsetDateTime.now());
        rbacRoleMapper.updateById(role);
        afterChange(operator, "role", id, "update", "UPDATED");
        return role;
    }

    public void deleteRole(String operator, Long id) {
        RbacRole role = requireRole(id);
        requireNotSystem(role);
        rbacRoleMapper.deleteById(id);
        resourceGrantMapper.delete(new LambdaQueryWrapper<ResourceGrant>()
                .eq(ResourceGrant::getGranteeType, "role")
                .eq(ResourceGrant::getGranteeId, id));
        afterChange(operator, "role", id, "delete", "DELETED");
    }

    public List<RbacPermission> listPermissions() {
        return rbacPermissionMapper.selectList(new LambdaQueryWrapper<RbacPermission>()
                .orderByAsc(RbacPermission::getResourceType)
                .orderByAsc(RbacPermission::getAction));
    }

    public List<String> rolePermissionCodes(Long roleId) {
        requireRole(roleId);
        return rbacRoleMapper.selectPermissionCodes(roleId);
    }

    @Transactional
    public void bindRolePermissions(String operator, Long roleId, List<String> codes) {
        RbacRole role = requireRole(roleId);
        requireNotSystem(role);
        Set<String> known = new HashSet<>();
        rbacPermissionMapper.selectList(null)
                .forEach(permission -> known.add(permission.getCode()));
        for (String code : codes) {
            if (!known.contains(code)) {
                throw new InvalidRequestException("未知权限码：" + code);
            }
        }
        rbacRoleMapper.deletePermissions(roleId);
        for (String code : new HashSet<>(codes)) {
            rbacRoleMapper.insertPermission(roleId, code);
        }
        afterChange(operator, "role", roleId, "bind", "UPDATED");
    }

    // ------------------------------------------------------------------
    // 用户组
    // ------------------------------------------------------------------

    public List<UserGroup> listGroups() {
        return userGroupMapper.selectList(
                new LambdaQueryWrapper<UserGroup>().orderByAsc(UserGroup::getId));
    }

    public UserGroup createGroup(String operator, UserGroupRequest request) {
        if (userGroupMapper.selectCount(new LambdaQueryWrapper<UserGroup>()
                .eq(UserGroup::getCode, request.code())) > 0) {
            throw new ConflictException("用户组编码已存在：" + request.code());
        }
        UserGroup group = new UserGroup();
        group.setCode(request.code());
        group.setName(request.name());
        group.setDescription(request.description());
        userGroupMapper.insert(group);
        afterChange(operator, "user_group", group.getId(), "create", "GRANTED");
        return group;
    }

    public UserGroup updateGroup(String operator, Long id, UserGroupRequest request) {
        UserGroup group = requireGroup(id);
        if (userGroupMapper.selectCount(new LambdaQueryWrapper<UserGroup>()
                .eq(UserGroup::getCode, request.code()).ne(UserGroup::getId, id)) > 0) {
            throw new ConflictException("用户组编码已存在：" + request.code());
        }
        group.setCode(request.code());
        group.setName(request.name());
        group.setDescription(request.description());
        group.setUpdatedAt(OffsetDateTime.now());
        userGroupMapper.updateById(group);
        afterChange(operator, "user_group", id, "update", "UPDATED");
        return group;
    }

    public void deleteGroup(String operator, Long id) {
        requireGroup(id);
        userGroupMapper.deleteById(id);
        resourceGrantMapper.delete(new LambdaQueryWrapper<ResourceGrant>()
                .eq(ResourceGrant::getGranteeType, "group")
                .eq(ResourceGrant::getGranteeId, id));
        afterChange(operator, "user_group", id, "delete", "DELETED");
    }

    public List<GroupMemberView> listGroupMembers(Long groupId) {
        requireGroup(groupId);
        return userGroupMapper.selectMemberIds(groupId).stream()
                .map(authUserService::getById)
                .filter(user -> user != null)
                .map(user -> new GroupMemberView(user.getId(), user.getUsername(), user.getNickname()))
                .toList();
    }

    @Transactional
    public void addGroupMembers(String operator, Long groupId, List<Long> userIds) {
        requireGroup(groupId);
        for (Long userId : new HashSet<>(userIds)) {
            if (authUserService.getById(userId) == null) {
                throw new NotFoundException("用户不存在：" + userId);
            }
            userGroupMapper.insertMember(groupId, userId);
        }
        afterChange(operator, "user_group", groupId, "member_add", "UPDATED");
    }

    public void removeGroupMember(String operator, Long groupId, Long userId) {
        requireGroup(groupId);
        userGroupMapper.deleteMember(groupId, userId);
        afterChange(operator, "user_group", groupId, "member_remove", "UPDATED");
    }

    // ------------------------------------------------------------------
    // 用户角色绑定
    // ------------------------------------------------------------------

    public List<String> userRoleCodes(Long userId) {
        requireUser(userId);
        return userRoleMapper.selectRoleCodesOfUser(userId);
    }

    @Transactional
    public void bindUserRoles(String operator, Long userId, List<String> roleCodes) {
        requireUser(userId);
        Map<String, RbacRole> rolesByCode = new HashMap<>();
        rbacRoleMapper.selectList(null).forEach(role -> rolesByCode.put(role.getCode(), role));
        for (String code : new HashSet<>(roleCodes)) {
            if (!rolesByCode.containsKey(code)) {
                throw new InvalidRequestException("未知角色编码：" + code);
            }
        }
        userRoleMapper.deleteAllOfUser(userId);
        for (String code : new HashSet<>(roleCodes)) {
            userRoleMapper.insert(userId, rolesByCode.get(code).getId());
        }
        afterChange(operator, "user_role", userId, "bind", "UPDATED");
    }

    // ------------------------------------------------------------------
    // 组织与项目空间
    // ------------------------------------------------------------------

    public List<Organization> listOrganizations() {
        return organizationMapper.selectList(
                new LambdaQueryWrapper<Organization>().orderByAsc(Organization::getId));
    }

    public Organization createOrganization(String operator, OrganizationRequest request) {
        ensureOrgCodeFree(request.code(), null);
        Organization organization = new Organization();
        organization.setCode(request.code());
        organization.setName(request.name());
        organization.setDescription(request.description());
        organizationMapper.insert(organization);
        audit(operator, "organization", organization.getId(), "create", "UPDATED");
        return organization;
    }

    public Organization updateOrganization(String operator, Long id, OrganizationRequest request) {
        Organization organization = requireOrganization(id);
        ensureOrgCodeFree(request.code(), id);
        organization.setCode(request.code());
        organization.setName(request.name());
        organization.setDescription(request.description());
        organization.setUpdatedAt(OffsetDateTime.now());
        organizationMapper.updateById(organization);
        audit(operator, "organization", id, "update", "UPDATED");
        return organization;
    }

    public void deleteOrganization(String operator, Long id) {
        requireOrganization(id);
        if (workspaceMapper.selectCount(new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getOrganizationId, id)) > 0) {
            throw new ConflictException("组织下存在项目空间，无法删除");
        }
        organizationMapper.deleteById(id);
        audit(operator, "organization", id, "delete", "DELETED");
    }

    public List<Workspace> listWorkspaces(Long organizationId) {
        LambdaQueryWrapper<Workspace> query = new LambdaQueryWrapper<Workspace>()
                .orderByAsc(Workspace::getId);
        if (organizationId != null) {
            query.eq(Workspace::getOrganizationId, organizationId);
        }
        return workspaceMapper.selectList(query);
    }

    public Workspace createWorkspace(String operator, WorkspaceRequest request) {
        requireOrganization(request.organizationId());
        ensureWorkspaceCodeFree(request.code(), null);
        Workspace workspace = new Workspace();
        workspace.setOrganizationId(request.organizationId());
        workspace.setCode(request.code());
        workspace.setName(request.name());
        workspace.setDescription(request.description());
        workspace.setStatus(request.status());
        workspaceMapper.insert(workspace);
        audit(operator, "workspace", workspace.getId(), "create", "UPDATED");
        return workspace;
    }

    public Workspace updateWorkspace(String operator, Long id, WorkspaceRequest request) {
        Workspace workspace = requireWorkspace(id);
        requireOrganization(request.organizationId());
        ensureWorkspaceCodeFree(request.code(), id);
        workspace.setOrganizationId(request.organizationId());
        workspace.setCode(request.code());
        workspace.setName(request.name());
        workspace.setDescription(request.description());
        workspace.setStatus(request.status());
        workspace.setUpdatedAt(OffsetDateTime.now());
        workspaceMapper.updateById(workspace);
        audit(operator, "workspace", id, "update", "UPDATED");
        return workspace;
    }

    public void deleteWorkspace(String operator, Long id) {
        requireWorkspace(id);
        workspaceMapper.deleteById(id);
        audit(operator, "workspace", id, "delete", "DELETED");
    }

    // ------------------------------------------------------------------
    // 资源授权
    // ------------------------------------------------------------------

    public List<GrantView> listGrants(String granteeType, String resourceType, Long resourceId) {
        LambdaQueryWrapper<ResourceGrant> query = new LambdaQueryWrapper<ResourceGrant>()
                .orderByAsc(ResourceGrant::getId);
        if (granteeType != null && !granteeType.isBlank()) {
            query.eq(ResourceGrant::getGranteeType, granteeType);
        }
        if (resourceType != null && !resourceType.isBlank()) {
            query.eq(ResourceGrant::getResourceType, resourceType);
        }
        if (resourceId != null) {
            query.eq(ResourceGrant::getResourceId, resourceId);
        }
        return resourceGrantMapper.selectList(query).stream().map(this::toView).toList();
    }

    public GrantView createGrant(String operator, GrantRequest request) {
        validateGrant(request.granteeType(), request.granteeId(), request.resourceType(),
                request.actions(), request.dataScope());
        ResourceGrant grant = new ResourceGrant();
        grant.setGranteeType(request.granteeType());
        grant.setGranteeId(request.granteeId());
        grant.setResourceType(request.resourceType());
        grant.setResourceId(request.resourceId());
        grant.setDataScope(request.dataScope());
        grant.setActions(request.actions());
        grant.setEnabled(request.enabled() == null || request.enabled());
        grant.setGrantedBy(operator);
        resourceGrantMapper.insert(grant);
        afterChange(operator, "grant", grant.getId(), "create", "GRANTED");
        return toView(grant);
    }

    public GrantView updateGrant(String operator, Long id, GrantUpdateRequest request) {
        ResourceGrant grant = requireGrant(id);
        validateGrant(grant.getGranteeType(), grant.getGranteeId(), grant.getResourceType(),
                request.actions(), request.dataScope());
        grant.setDataScope(request.dataScope());
        grant.setActions(request.actions());
        grant.setEnabled(request.enabled() == null || request.enabled());
        grant.setUpdatedAt(OffsetDateTime.now());
        resourceGrantMapper.updateById(grant);
        afterChange(operator, "grant", id, "update", "UPDATED");
        return toView(grant);
    }

    public void revokeGrant(String operator, Long id) {
        requireGrant(id);
        resourceGrantMapper.deleteById(id);
        afterChange(operator, "grant", id, "delete", "REVOKED");
    }

    // ------------------------------------------------------------------
    // 校验与辅助
    // ------------------------------------------------------------------

    private void validateGrant(String granteeType, Long granteeId, String resourceType,
                               List<String> actions, DataScope dataScope) {
        if (!GRANTEE_TYPES.contains(granteeType)) {
            throw new InvalidRequestException("非法授权主体类型：" + granteeType);
        }
        boolean granteeExists = switch (granteeType) {
            case "user" -> authUserService.getById(granteeId) != null;
            case "group" -> userGroupMapper.selectById(granteeId) != null;
            case "role" -> rbacRoleMapper.selectById(granteeId) != null;
            default -> false;
        };
        if (!granteeExists) {
            throw new NotFoundException("授权主体不存在：" + granteeType + ":" + granteeId);
        }
        if (!rbacPermissionMapper.selectResourceTypes().contains(resourceType)) {
            throw new InvalidRequestException("未知资源类型：" + resourceType);
        }
        List<String> allowedActions = rbacPermissionMapper.selectActions(resourceType);
        for (String action : actions) {
            if (!allowedActions.contains(action)) {
                throw new InvalidRequestException(
                        "资源类型 " + resourceType + " 不支持动作 " + action);
            }
        }
        if (dataScope != null && !dataScope.isEmpty()) {
            if (!DATA_SCOPE_KINDS.contains(dataScope.getKind())) {
                throw new InvalidRequestException("非法数据范围类型：" + dataScope.getKind());
            }
            if (dataScope.getValues().isEmpty()) {
                throw new InvalidRequestException("数据范围 values 不能为空");
            }
        }
    }

    private GrantView toView(ResourceGrant grant) {
        return new GrantView(
                grant.getId(),
                grant.getGranteeType(),
                grant.getGranteeId(),
                granteeName(grant.getGranteeType(), grant.getGranteeId()),
                grant.getResourceType(),
                grant.getResourceId(),
                grant.getDataScope(),
                grant.getActions(),
                grant.getEnabled(),
                grant.getGrantedBy(),
                grant.getCreatedAt(),
                grant.getUpdatedAt());
    }

    private String granteeName(String granteeType, Long granteeId) {
        return switch (granteeType) {
            case "user" -> {
                AuthUser user = authUserService.getById(granteeId);
                yield user == null ? String.valueOf(granteeId)
                        : user.getNickname() == null || user.getNickname().isBlank()
                            ? user.getUsername() : user.getUsername() + "(" + user.getNickname() + ")";
            }
            case "group" -> {
                UserGroup group = userGroupMapper.selectById(granteeId);
                yield group == null ? String.valueOf(granteeId) : group.getName();
            }
            case "role" -> {
                RbacRole role = rbacRoleMapper.selectById(granteeId);
                yield role == null ? String.valueOf(granteeId) : role.getName();
            }
            default -> String.valueOf(granteeId);
        };
    }

    private RbacRole requireRole(Long id) {
        RbacRole role = rbacRoleMapper.selectById(id);
        if (role == null) {
            throw new NotFoundException("角色不存在：" + id);
        }
        return role;
    }

    private void requireNotSystem(RbacRole role) {
        if (Boolean.TRUE.equals(role.getIsSystem())
                || SYSTEM_ROLE_CODES.contains(role.getCode())) {
            throw new InvalidRequestException("系统角色不允许修改：" + role.getCode());
        }
    }

    private void ensureRoleCodeFree(String code, Long selfId) {
        LambdaQueryWrapper<RbacRole> query = new LambdaQueryWrapper<RbacRole>()
                .eq(RbacRole::getCode, code);
        if (selfId != null) {
            query.ne(RbacRole::getId, selfId);
        }
        if (rbacRoleMapper.selectCount(query) > 0) {
            throw new ConflictException("角色编码已存在：" + code);
        }
    }

    private UserGroup requireGroup(Long id) {
        UserGroup group = userGroupMapper.selectById(id);
        if (group == null) {
            throw new NotFoundException("用户组不存在：" + id);
        }
        return group;
    }

    private AuthUser requireUser(Long id) {
        AuthUser user = authUserService.getById(id);
        if (user == null) {
            throw new NotFoundException("用户不存在：" + id);
        }
        return user;
    }

    private Organization requireOrganization(Long id) {
        Organization organization = organizationMapper.selectById(id);
        if (organization == null) {
            throw new NotFoundException("组织不存在：" + id);
        }
        return organization;
    }

    private void ensureOrgCodeFree(String code, Long selfId) {
        LambdaQueryWrapper<Organization> query = new LambdaQueryWrapper<Organization>()
                .eq(Organization::getCode, code);
        if (selfId != null) {
            query.ne(Organization::getId, selfId);
        }
        if (organizationMapper.selectCount(query) > 0) {
            throw new ConflictException("组织编码已存在：" + code);
        }
    }

    private Workspace requireWorkspace(Long id) {
        Workspace workspace = workspaceMapper.selectById(id);
        if (workspace == null) {
            throw new NotFoundException("项目空间不存在：" + id);
        }
        return workspace;
    }

    private void ensureWorkspaceCodeFree(String code, Long selfId) {
        LambdaQueryWrapper<Workspace> query = new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getCode, code);
        if (selfId != null) {
            query.ne(Workspace::getId, selfId);
        }
        if (workspaceMapper.selectCount(query) > 0) {
            throw new ConflictException("项目空间编码已存在：" + code);
        }
    }

    private ResourceGrant requireGrant(Long id) {
        ResourceGrant grant = resourceGrantMapper.selectById(id);
        if (grant == null) {
            throw new NotFoundException("授权不存在：" + id);
        }
        return grant;
    }

    /** 影响决策输入的变更：持久化成功后递增权限版本并审计。 */
    private void afterChange(String operator, String resourceType, Long resourceId,
                             String action, String decision) {
        permissionVersionService.bump();
        audit(operator, resourceType, resourceId, action, decision);
    }

    private void audit(String operator, String resourceType, Long resourceId,
                       String action, String decision) {
        auditClient.record(new PermissionEventRequest(
                operator, null, resourceType, String.valueOf(resourceId), action,
                decision, null, null));
    }
}