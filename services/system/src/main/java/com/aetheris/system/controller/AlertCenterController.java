package com.aetheris.system.controller;

import com.aetheris.system.entity.Notification;
import com.aetheris.system.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警中心（运维视角）。与「我的待办」共用 notification 表，但视角不同：
 * admin 看全局告警（所有人的），普通用户只看自己的。
 * 独立端点而非复用 /notifications，避免改动既有接口的行为。
 */
@RestController
@RequestMapping("/api/system/alerts")
public class AlertCenterController {

    private static final String ADMIN_ROLE = "admin";

    private final NotificationService notificationService;

    public AlertCenterController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 告警列表：level/source 可选过滤，按时间倒序取最近 200 条。 */
    @GetMapping
    public List<Notification> list(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean unread) {
        boolean isAdmin = ADMIN_ROLE.equals(role);
        LambdaQueryWrapper<Notification> query = new LambdaQueryWrapper<Notification>()
                // admin 看全局（运维职责），普通用户只看自己相关的
                .eq(!isAdmin, Notification::getRecipient, username)
                .eq(level != null && !level.isBlank(), Notification::getLevel, level)
                .eq(source != null && !source.isBlank(), Notification::getSource, source)
                .eq(Boolean.TRUE.equals(unread), Notification::getIsRead, false)
                .orderByDesc(Notification::getId)
                .last("LIMIT 200");
        return notificationService.list(query);
    }

    /** 告警统计：按级别汇总当前视角可见的告警数。 */
    @GetMapping("/stats")
    public Map<String, Long> stats(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestHeader(value = "X-User-Role", defaultValue = "viewer") String role) {
        boolean isAdmin = ADMIN_ROLE.equals(role);
        Map<String, Long> stats = new HashMap<>();
        long total = 0;
        for (String level : List.of("critical", "warning", "info")) {
            long count = notificationService.count(new LambdaQueryWrapper<Notification>()
                    .eq(!isAdmin, Notification::getRecipient, username)
                    .eq(Notification::getLevel, level));
            stats.put(level, count);
            total += count;
        }
        stats.put("total", total);
        return stats;
    }
}
