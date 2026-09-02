package com.aetheris.system.controller;

import com.aetheris.system.entity.Notification;
import com.aetheris.system.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 面向当前用户的通知（我的待办）。用户名来自网关注入的 X-User-Name，
 * 客户端不可伪造。
 */
@RestController
@RequestMapping("/api/system/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<Notification> list(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username,
            @RequestParam(required = false) Boolean unread) {
        return notificationService.list(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipient, username)
                .eq(Boolean.TRUE.equals(unread), Notification::getIsRead, false)
                .orderByDesc(Notification::getId)
                .last("LIMIT 200"));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        long count = notificationService.count(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipient, username)
                .eq(Notification::getIsRead, false));
        return Map.of("count", count);
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long id,
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        // 条件带 recipient：不能把别人的通知标记为已读。
        boolean updated = notificationService.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getRecipient, username)
                .set(Notification::getIsRead, true));
        return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllRead(
            @RequestHeader(value = "X-User-Name", defaultValue = "unknown") String username) {
        notificationService.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipient, username)
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true));
        return ResponseEntity.noContent().build();
    }
}
