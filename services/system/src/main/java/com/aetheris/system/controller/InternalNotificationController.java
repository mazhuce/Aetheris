package com.aetheris.system.controller;

import com.aetheris.system.security.SystemInternalRequests;
import com.aetheris.system.service.OperationsInboxStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Fixed receiver inbox, durable caller/event identity, no body-selected subject or destination. */
@RestController
@RequestMapping("/internal/v2/notifications")
public class InternalNotificationController {
    private final OperationsInboxStore store;
    private final SystemInternalRequests bodies;
    public InternalNotificationController(OperationsInboxStore store,SystemInternalRequests bodies){this.store=store;this.bodies=bodies;}
    @PostMapping public ResponseEntity<Void> create(HttpServletRequest request){
        String caller=bodies.caller(request,"system.notification.record",OperationsInboxStore.CALLERS);
        var event=bodies.operationsEvent(request);
        store.record(caller,event);
        return ResponseEntity.accepted().build();
    }
}
