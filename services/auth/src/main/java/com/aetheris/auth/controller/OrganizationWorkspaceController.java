package com.aetheris.auth.controller;

import com.aetheris.auth.dto.OrganizationRequest;
import com.aetheris.auth.dto.WorkspaceRequest;
import com.aetheris.auth.entity.Organization;
import com.aetheris.auth.entity.Workspace;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 组织与项目空间管理（数据范围容器）。所有端点要求系统管理权限。 */
@RestController
@RequestMapping("/api/auth/rbac")
public class OrganizationWorkspaceController {

    private static final String USER_HEADER = "X-User-Name";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RbacAdminService service;
    private final AdminGuard adminGuard;

    public OrganizationWorkspaceController(RbacAdminService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/organizations")
    public List<Organization> listOrganizations(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        guard(requester, requestId);
        return service.listOrganizations();
    }

    @PostMapping("/organizations")
    public ResponseEntity<Organization> createOrganization(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @Valid @RequestBody OrganizationRequest request) {
        guard(requester, requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createOrganization(requester, request));
    }

    @PutMapping("/organizations/{id}")
    public Organization updateOrganization(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody OrganizationRequest request) {
        guard(requester, requestId);
        return service.updateOrganization(requester, id, request);
    }

    @DeleteMapping("/organizations/{id}")
    public ResponseEntity<Void> deleteOrganization(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        service.deleteOrganization(requester, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/workspaces")
    public List<Workspace> listWorkspaces(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestParam(required = false) Long organizationId) {
        guard(requester, requestId);
        return service.listWorkspaces(organizationId);
    }

    @PostMapping("/workspaces")
    public ResponseEntity<Workspace> createWorkspace(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @Valid @RequestBody WorkspaceRequest request) {
        guard(requester, requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createWorkspace(requester, request));
    }

    @PutMapping("/workspaces/{id}")
    public Workspace updateWorkspace(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody WorkspaceRequest request) {
        guard(requester, requestId);
        return service.updateWorkspace(requester, id, request);
    }

    @DeleteMapping("/workspaces/{id}")
    public ResponseEntity<Void> deleteWorkspace(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        service.deleteWorkspace(requester, id);
        return ResponseEntity.noContent().build();
    }

    private void guard(String requester, String requestId) {
        if (requester == null) {
            throw new AccessDeniedException("缺少认证主体");
        }
        adminGuard.requireManage(requester, requestId);
    }
}