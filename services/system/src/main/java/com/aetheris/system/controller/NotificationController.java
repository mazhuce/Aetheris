package com.aetheris.system.controller;

import com.aetheris.system.entity.Notification;
import com.aetheris.system.security.NotificationOwner;
import com.aetheris.system.service.NotificationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Personal inbox: stable UID only. Operations events have separate managed acknowledgement endpoints.
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
            HttpServletRequest request,
            @RequestParam(required = false) Boolean unread) {
        NotificationOwner owner = NotificationOwner.require(request);
        return notificationService.list(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getInboxKind, "personal").eq(Notification::getRecipientUid, owner.uid())
                .eq(Boolean.TRUE.equals(unread), Notification::getIsRead, false)
                .orderByDesc(Notification::getId)
                .last("LIMIT 200"));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(
            HttpServletRequest request) {
        NotificationOwner owner = NotificationOwner.require(request);
        long count = notificationService.count(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getInboxKind, "personal").eq(Notification::getRecipientUid, owner.uid())
                .eq(Notification::getIsRead, false));
        return Map.of("count", count);
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable Long id,
            HttpServletRequest request) {
        NotificationOwner owner = NotificationOwner.require(request);
        boolean updated = notificationService.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getInboxKind, "personal").eq(Notification::getRecipientUid, owner.uid())
                .set(Notification::getIsRead, true));
        return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllRead(
            HttpServletRequest request) {
        NotificationOwner owner = NotificationOwner.require(request);
        notificationService.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getInboxKind, "personal").eq(Notification::getRecipientUid, owner.uid())
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true));
        return ResponseEntity.noContent().build();
    }

}
