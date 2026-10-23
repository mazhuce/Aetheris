package com.aetheris.system.controller;

import com.aetheris.system.dto.PermissionEventRequest;
import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.service.PermissionEventService;
import jakarta.servlet.http.HttpServletRequest;
import com.aetheris.system.security.SystemInternalRequests;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Auth-only v2 permission journal. Body fields are observations, never caller credentials. */
@RestController
@RequestMapping("/internal/v2/audit/permission-events")
public class InternalPermissionEventController {

    private final PermissionEventService permissionEventService;
    private final SystemInternalRequests bodies;

    public InternalPermissionEventController(PermissionEventService permissionEventService,SystemInternalRequests bodies) {
        this.permissionEventService = permissionEventService;
        this.bodies = bodies;
    }

    @PostMapping
    public ResponseEntity<Void> create(HttpServletRequest http) {
        bodies.caller(http,"system.permission-event.record",Set.of("auth"));
        PermissionEventRequest request=bodies.read(http,PermissionEventRequest.class);
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
