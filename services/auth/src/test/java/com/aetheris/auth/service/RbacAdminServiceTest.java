package com.aetheris.auth.service;

import com.aetheris.auth.audit.PermissionAuditClient;
import com.aetheris.auth.audit.PermissionEventRequest;
import com.aetheris.auth.dto.GrantRequest;
import com.aetheris.auth.dto.RbacRoleRequest;
import com.aetheris.auth.entity.AuthUser;
import com.aetheris.auth.entity.Organization;
import com.aetheris.auth.entity.RbacPermission;
import com.aetheris.auth.entity.RbacRole;
import com.aetheris.auth.entity.ResourceGrant;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RbacAdminServiceTest {

    private AuthUserService authUserService;
    private RbacRoleMapper rbacRoleMapper;
    private RbacPermissionMapper rbacPermissionMapper;
    private UserGroupMapper userGroupMapper;
    private UserRoleMapper userRoleMapper;
    private OrganizationMapper organizationMapper;
    private WorkspaceMapper workspaceMapper;
    private ResourceGrantMapper resourceGrantMapper;
    private PermissionVersionService permissionVersionService;
    private PermissionAuditClient auditClient;
    private RbacAdminService service;

    @BeforeEach
    void setUp() {
        authUserService = mock(AuthUserService.class);
        rbacRoleMapper = mock(RbacRoleMapper.class);
        rbacPermissionMapper = mock(RbacPermissionMapper.class);
        userGroupMapper = mock(UserGroupMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        organizationMapper = mock(OrganizationMapper.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        resourceGrantMapper = mock(ResourceGrantMapper.class);
        permissionVersionService = mock(PermissionVersionService.class);
        auditClient = mock(PermissionAuditClient.class);
        service = new RbacAdminService(authUserService, rbacRoleMapper, rbacPermissionMapper,
                userGroupMapper, userRoleMapper, organizationMapper, workspaceMapper,
                resourceGrantMapper, permissionVersionService, auditClient);
    }

    private static AuthUser user(long id, String username) {
        AuthUser user = new AuthUser();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username + "-nick");
        return user;
    }

    private static RbacRole role(long id, String code, boolean system) {
        RbacRole role = new RbacRole();
        role.setId(id);
        role.setCode(code);
        role.setName(code);
        role.setIsSystem(system);
        return role;
    }

    @Test
    void createRolePersistsBumpsVersionAndAudits() {
        when(rbacRoleMapper.selectCount(any())).thenReturn(0L);

        service.createRole("admin", new RbacRoleRequest("data_owner", "数据负责人", null));

        ArgumentCaptor<RbacRole> roleCaptor = ArgumentCaptor.forClass(RbacRole.class);
        verify(rbacRoleMapper).insert(roleCaptor.capture());
        assertEquals("data_owner", roleCaptor.getValue().getCode());
        verify(permissionVersionService).bump();
        verify(auditClient).record(any(PermissionEventRequest.class));
    }

    @Test
    void createRoleRejectsDuplicateCode() {
        when(rbacRoleMapper.selectCount(any())).thenReturn(1L);

        assertThrows(ConflictException.class,
                () -> service.createRole("admin", new RbacRoleRequest("data_owner", "数据负责人", null)));
        verify(rbacRoleMapper, never()).insert(any(RbacRole.class));
        verify(permissionVersionService, never()).bump();
    }

    @Test
    void deleteRoleRejectsSystemRole() {
        when(rbacRoleMapper.selectById(1L)).thenReturn(role(1L, "sys_admin", true));

        assertThrows(InvalidRequestException.class, () -> service.deleteRole("admin", 1L));
        verify(rbacRoleMapper, never()).deleteById((java.io.Serializable) any());
        verify(permissionVersionService, never()).bump();
    }

    @Test
    void bindRolePermissionsRejectsUnknownCode() {
        when(rbacRoleMapper.selectById(5L)).thenReturn(role(5L, "data_owner", false));
        when(rbacPermissionMapper.selectList(any())).thenReturn(List.of(
                permission("metric.view", "metric", "view")));

        assertThrows(InvalidRequestException.class,
                () -> service.bindRolePermissions("admin", 5L, List.of("ghost.action")));
        verify(rbacRoleMapper, never()).deletePermissions(any());
        verify(permissionVersionService, never()).bump();
    }

    private static RbacPermission permission(String code, String resourceType, String action) {
        RbacPermission permission = new RbacPermission();
        permission.setCode(code);
        permission.setResourceType(resourceType);
        permission.setAction(action);
        return permission;
    }

    @Test
    void bindUserRolesRejectsUnknownRoleCode() {
        when(authUserService.getById(10L)).thenReturn(user(10L, "alice"));
        when(rbacRoleMapper.selectList(any())).thenReturn(List.of(role(3L, "analyst", false)));

        assertThrows(InvalidRequestException.class,
                () -> service.bindUserRoles("admin", 10L, List.of("ghost_role")));
        verify(userRoleMapper, never()).deleteAllOfUser(any());
        verify(permissionVersionService, never()).bump();
    }

    @Test
    void createGrantRejectsUnknownResourceType() {
        when(authUserService.getById(7L)).thenReturn(user(7L, "alice"));
        when(rbacPermissionMapper.selectResourceTypes()).thenReturn(List.of("table", "metric"));

        GrantRequest request = new GrantRequest("user", 7L, "hologram", 1L, null,
                List.of("view"), null);
        assertThrows(InvalidRequestException.class, () -> service.createGrant("admin", request));
        verify(resourceGrantMapper, never()).insert(any(ResourceGrant.class));
    }

    @Test
    void createGrantRejectsUnsupportedAction() {
        when(authUserService.getById(7L)).thenReturn(user(7L, "alice"));
        when(rbacPermissionMapper.selectResourceTypes()).thenReturn(List.of("metric"));
        when(rbacPermissionMapper.selectActions("metric")).thenReturn(List.of("view", "query"));

        GrantRequest request = new GrantRequest("user", 7L, "metric", 5L, null,
                List.of("publish"), null);
        assertThrows(InvalidRequestException.class, () -> service.createGrant("admin", request));
    }

    @Test
    void createGrantRejectsInvalidScopeKind() {
        when(authUserService.getById(7L)).thenReturn(user(7L, "alice"));
        when(rbacPermissionMapper.selectResourceTypes()).thenReturn(List.of("metric"));
        when(rbacPermissionMapper.selectActions("metric")).thenReturn(List.of("query"));

        GrantRequest request = new GrantRequest("user", 7L, "metric", 5L,
                new DataScope("galaxy", List.of("1")), List.of("query"), null);
        assertThrows(InvalidRequestException.class, () -> service.createGrant("admin", request));
    }

    @Test
    void createGrantRejectsUnregisteredGrantee() {
        when(authUserService.getById(99L)).thenReturn(null);

        GrantRequest request = new GrantRequest("user", 99L, "metric", 5L, null,
                List.of("query"), null);
        assertThrows(NotFoundException.class, () -> service.createGrant("admin", request));
    }

    @Test
    void createGrantPersistsDefaultEnabledBumpsVersionAndAuditsGrated() {
        when(authUserService.getById(7L)).thenReturn(user(7L, "alice"));
        when(rbacPermissionMapper.selectResourceTypes()).thenReturn(List.of("metric"));
        when(rbacPermissionMapper.selectActions("metric")).thenReturn(List.of("view", "query"));

        service.createGrant("admin", new GrantRequest("user", 7L, "metric", 5L,
                new DataScope("workspace", List.of("1")), List.of("view"), null));

        ArgumentCaptor<ResourceGrant> grantCaptor = ArgumentCaptor.forClass(ResourceGrant.class);
        verify(resourceGrantMapper).insert(grantCaptor.capture());
        assertTrue(grantCaptor.getValue().getEnabled());
        assertEquals(List.of("view"), grantCaptor.getValue().getActions());
        assertEquals("admin", grantCaptor.getValue().getGrantedBy());
        verify(permissionVersionService).bump();
        ArgumentCaptor<PermissionEventRequest> eventCaptor =
                ArgumentCaptor.forClass(PermissionEventRequest.class);
        verify(auditClient).record(eventCaptor.capture());
        assertEquals("GRANTED", eventCaptor.getValue().getDecision());
    }

    @Test
    void revokeGrantDeletesBumpsAndAuditsRevoked() {
        ResourceGrant grant = new ResourceGrant();
        grant.setId(11L);
        when(resourceGrantMapper.selectById(11L)).thenReturn(grant);

        service.revokeGrant("admin", 11L);

        verify(resourceGrantMapper).deleteById(11L);
        verify(permissionVersionService).bump();
        ArgumentCaptor<PermissionEventRequest> eventCaptor =
                ArgumentCaptor.forClass(PermissionEventRequest.class);
        verify(auditClient).record(eventCaptor.capture());
        assertEquals("REVOKED", eventCaptor.getValue().getDecision());
    }

    @Test
    void revokeGrantMissingThrowsNotFound() {
        when(resourceGrantMapper.selectById(404L)).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.revokeGrant("admin", 404L));
        verify(resourceGrantMapper, never()).deleteById((java.io.Serializable) any());
    }

    @Test
    void deleteOrganizationWithWorkspaceIsRejected() {
        Organization organization = new Organization();
        organization.setId(2L);
        when(organizationMapper.selectById(2L)).thenReturn(organization);
        when(workspaceMapper.selectCount(any())).thenReturn(3L);

        assertThrows(ConflictException.class, () -> service.deleteOrganization("admin", 2L));
        verify(organizationMapper, never()).deleteById((java.io.Serializable) any());
    }

    @Test
    void deleteOrganizationWithoutWorkspaceSucceeds() {
        Organization organization = new Organization();
        organization.setId(2L);
        when(organizationMapper.selectById(2L)).thenReturn(organization);
        when(workspaceMapper.selectCount(any())).thenReturn(0L);

        service.deleteOrganization("admin", 2L);

        verify(organizationMapper).deleteById(2L);
        verify(auditClient).record(any(PermissionEventRequest.class));
    }
}