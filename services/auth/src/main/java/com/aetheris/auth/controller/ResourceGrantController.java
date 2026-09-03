package com.aetheris.auth.controller;

import com.aetheris.auth.dto.GrantRequest;
import com.aetheris.auth.dto.GrantUpdateRequest;
import com.aetheris.auth.dto.GrantView;
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

/**
 * 资源授权管理：授权新增、修改、撤销与列表。
 *
 * <p>每次授权/修改/撤销都会校验动作合法性、递增权限版本并写入审计事件。
 * 所有端点要求系统管理权限（service.admin.manage）。
 */
@RestController
@RequestMapping("/api/auth/rbac/grants")
public class ResourceGrantController {

    private static final String USER_HEADER = "X-User-Name";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RbacAdminService service;
    private final AdminGuard adminGuard;

    public ResourceGrantController(RbacAdminService service, AdminGuard adminGuard) {
        this.service = service;
        this.adminGuard = adminGuard;
    }

    @GetMapping
    public List<GrantView> list(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @RequestParam(required = false) String granteeType,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId) {
        guard(requester, requestId);
        return service.listGrants(granteeType, resourceType, resourceId);
    }

    @PostMapping
    public ResponseEntity<GrantView> create(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @Valid @RequestBody GrantRequest request) {
        guard(requester, requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createGrant(requester, request));
    }

    @PutMapping("/{id}")
    public GrantView update(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id,
            @Valid @RequestBody GrantUpdateRequest request) {
        guard(requester, requestId);
        return service.updateGrant(requester, id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(
            @RequestHeader(value = USER_HEADER, required = false) String requester,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId,
            @PathVariable Long id) {
        guard(requester, requestId);
        service.revokeGrant(requester, id);
        return ResponseEntity.noContent().build();
    }

    private void guard(String requester, String requestId) {
        if (requester == null) {
            throw new AccessDeniedException("缺少认证主体");
        }
        adminGuard.requireManage(requester, requestId);
    }
}