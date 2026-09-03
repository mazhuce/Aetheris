package com.aetheris.system.controller;

import com.aetheris.system.dto.PermissionEventRequest;
import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.service.PermissionEventService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限审计事件内部写入端点：auth-service 的授权、撤销、拒绝与越权尝试
 * 在这里统一落库。与 /internal/audit/logs 一致仅在内网可达（网关不路由
 * /internal/**，system-service 不映射宿主端口）。
 */
@RestController
@RequestMapping("/internal/audit/permission-events")
public class InternalPermissionEventController {

    private final PermissionEventService permissionEventService;

    public InternalPermissionEventController(PermissionEventService permissionEventService) {
        this.permissionEventService = permissionEventService;
    }

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody PermissionEventRequest request) {
        PermissionEvent event = new PermissionEvent();
        event.setUsername(request.username());
        event.setUserId(request.userId());
        event.setResourceType(request.resourceType());
        event.setResourceId(request.resourceId());
        event.setAction(request.action());
        event.setDecision(request.decision());
        event.setReason(request.reason());
        event.setRequestId(request.requestId());
        permissionEventService.save(event);
        return ResponseEntity.accepted().build();
    }
}