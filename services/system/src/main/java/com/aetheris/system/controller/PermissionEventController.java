package com.aetheris.system.controller;

import com.aetheris.system.entity.PermissionEvent;
import com.aetheris.system.service.PermissionEventService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限审计查询接口。读取受网关 may-manage 与系统管理权限保护
 * （/api/system/permission-events 属于敏感前缀），本服务的资源级判定在
 * 阶段二 A 全链路接入时统一补齐；目前入口已由网关 fail-closed 拦截。
 */
@RestController
@RequestMapping("/api/system/permission-events")
public class PermissionEventController {

    private final PermissionEventService permissionEventService;

    public PermissionEventController(PermissionEventService permissionEventService) {
        this.permissionEventService = permissionEventService;
    }

    @GetMapping
    public List<PermissionEvent> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String action) {
        LambdaQueryWrapper<PermissionEvent> query = new LambdaQueryWrapper<PermissionEvent>()
                .eq(StringUtils.hasText(username), PermissionEvent::getUsername, username)
                .eq(StringUtils.hasText(decision), PermissionEvent::getDecision, decision)
                .eq(StringUtils.hasText(resourceType), PermissionEvent::getResourceType, resourceType)
                .eq(StringUtils.hasText(action), PermissionEvent::getAction, action)
                .orderByDesc(PermissionEvent::getId)
                .last("LIMIT 200");
        return permissionEventService.list(query);
    }
}