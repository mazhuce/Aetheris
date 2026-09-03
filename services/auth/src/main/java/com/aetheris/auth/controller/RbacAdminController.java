package com.aetheris.auth.controller;

import com.aetheris.auth.dto.GroupMembersRequest;
import com.aetheris.auth.dto.GroupMemberView;
import com.aetheris.auth.dto.PermissionBindRequest;
import com.aetheris.auth.dto.RbacRoleRequest;
import com.aetheris.auth.dto.UserGroupRequest;
import com.aetheris.auth.dto.UserRoleBindRequest;
import com.aetheris.auth.entity.RbacPermission;
import com.aetheris.auth.entity.RbacRole;
import com.aetheris.auth.entity.UserGroup;
import com.aetheris.auth.service.AdminGuard;
import com.aetheris.auth.service.RbacAdminService;
import com.aetheris.auth.web.AccessDeniedException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理：角色、权限目录、用户组与用户角色绑定。
 *
 * <p>所有端点（含只读）都要求系统管理权限（service.admin.manage），
 * 判定由 AdminGuard 调用 auth 决策引擎完成，不依赖前端隐藏按钮。
 */
@RestController
@RequestMapping("/api/auth/rbac")
public class RbacAdminController {

    private static final String USER_HEADER = "X-User-Name";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RbacAdminService service;
    private final AdminGuard adminGuard;

    public RbacAdminController(RbacAdminService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    // ---- 角色 ----

    @GetMapping("/roles")
    public List<RbacRole> listRoles(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        guard(requester, requestId);
        return service.listRoles();
    }

    @PostMapping("/roles")
    public ResponseEntity<RbacRole> createRole(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @Valid @RequestBody RbacRoleRequest request) {
        guard(requester, requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createRole(requester, request));
    }

    @PutMapping("/roles/{id}")
    public RbacRole updateRole(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody RbacRoleRequest request) {
        guard(requester, requestId);
        return service.updateRole(requester, id, request);
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> deleteRole(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        service.deleteRole(requester, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/roles/{id}/permission-codes")
    public List<String> rolePermissionCodes(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        return service.rolePermissionCodes(id);
    }

    @PutMapping("/roles/{id}/permissions")
    public ResponseEntity<Void> bindRolePermissions(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody PermissionBindRequest request) {
        guard(requester, requestId);
        service.bindRolePermissions(requester, id, request.codes());
        return ResponseEntity.noContent().build();
    }

    // ---- 权限目录 ----

    @GetMapping("/permissions")
    public List<RbacPermission> listPermissions(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        guard(requester, requestId);
        return service.listPermissions();
    }

    // ---- 用户组 ----

    @GetMapping("/groups")
    public List<UserGroup> listGroups(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        guard(requester, requestId);
        return service.listGroups();
    }

    @PostMapping("/groups")
    public ResponseEntity<UserGroup> createGroup(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @Valid @RequestBody UserGroupRequest request) {
        guard(requester, requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createGroup(requester, request));
    }

    @PutMapping("/groups/{id}")
    public UserGroup updateGroup(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody UserGroupRequest request) {
        guard(requester, requestId);
        return service.updateGroup(requester, id, request);
    }

    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> deleteGroup(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        service.deleteGroup(requester, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/groups/{id}/members")
    public List<GroupMemberView> listGroupMembers(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        return service.listGroupMembers(id);
    }

    @PostMapping("/groups/{id}/members")
    public ResponseEntity<Void> addGroupMembers(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody GroupMembersRequest request) {
        guard(requester, requestId);
        service.addGroupMembers(requester, id, request.userIds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/groups/{id}/members/{userId}")
    public ResponseEntity<Void> removeGroupMember(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @PathVariable Long userId) {
        guard(requester, requestId);
        service.removeGroupMember(requester, id, userId);
        return ResponseEntity.noContent().build();
    }

    // ---- 用户角色绑定 ----

    @GetMapping("/users/{userId}/roles")
    public List<String> userRoleCodes(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long userId) {
        guard(requester, requestId);
        return service.userRoleCodes(userId);
    }

    @PutMapping("/users/{userId}/roles")
    public ResponseEntity<Void> bindUserRoles(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long userId,
            @Valid @RequestBody UserRoleBindRequest request) {
        guard(requester, requestId);
        service.bindUserRoles(requester, userId, request.roleCodes());
        return ResponseEntity.noContent().build();
    }

    private void guard(String requester, String requestId) {
        if (requester == null) {
            throw new AccessDeniedException("缺少认证主体");
        }
        adminGuard.requireManage(requester, requestId);
    }
}