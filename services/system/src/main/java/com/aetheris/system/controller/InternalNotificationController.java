package com.aetheris.system.controller;

import com.aetheris.system.dto.NotificationRequest;
import com.aetheris.system.entity.Notification;
import com.aetheris.system.service.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    private final NotificationService notificationService;

    public InternalNotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @PostMapping
    public ResponseEntity<Void> create(@Valid @RequestBody NotificationRequest request) {
        Notification notification = new Notification();
        notification.setRecipient(request.recipient());
        notification.setSource(request.source());
        notification.setSourceId(request.sourceId());
        notification.setLevel(request.level() == null || request.level().isBlank()
                ? "info" : request.level());
        notification.setTitle(request.title());
        notification.setMessage(request.message());
        notification.setLinkPath(request.linkPath());
        notification.setIsRead(false);
        notificationService.save(notification);
        return ResponseEntity.accepted().build();
    }
}
