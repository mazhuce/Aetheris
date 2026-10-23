package com.aetheris.system.controller;

import com.aetheris.security.servlet.TrustedUser;
import com.aetheris.system.entity.Notification;
import com.aetheris.system.security.AuthPermissionClient;
import com.aetheris.system.security.NotificationOwner;
import com.aetheris.system.service.NotificationService;
import com.aetheris.system.service.OperationsInboxStore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Shared operations inbox; every read/acknowledgement needs a current management decision. */
@RestController
@RequestMapping("/api/system/alerts")
public class AlertCenterController {
    private final NotificationService notifications;
    private final AuthPermissionClient permissions;
    private final OperationsInboxStore store;
    public AlertCenterController(NotificationService notifications,AuthPermissionClient permissions,OperationsInboxStore store){
        this.notifications=Objects.requireNonNull(notifications);this.permissions=Objects.requireNonNull(permissions);this.store=Objects.requireNonNull(store);
    }
    @GetMapping public List<Notification> list(HttpServletRequest request,@RequestParam(required=false)String level,
            @RequestParam(required=false)String source,@RequestParam(required=false)Boolean unread){
        requireManager(request);
        if(level!=null&&!level.isBlank()&&!Set.of("critical","warning","info").contains(level))throw new IllegalArgumentException("Invalid event level");
        if(source!=null&&!source.isBlank()&&!OperationsInboxStore.CALLERS.contains(source))throw new IllegalArgumentException("Invalid event source");
        return notifications.list(new LambdaQueryWrapper<Notification>().eq(Notification::getInboxKind,"operations")
                .eq(level!=null&&!level.isBlank(),Notification::getLevel,level)
                .eq(source!=null&&!source.isBlank(),Notification::getSource,source)
                .eq(Boolean.TRUE.equals(unread),Notification::getIsRead,false).orderByDesc(Notification::getId).last("LIMIT 200"));
    }
    @GetMapping("/stats") public Map<String,Long> stats(HttpServletRequest request){
        requireManager(request);Map<String,Long> result=new LinkedHashMap<>();long total=0;
        for(String level:List.of("critical","warning","info")){
            long count=notifications.count(new LambdaQueryWrapper<Notification>().eq(Notification::getInboxKind,"operations").eq(Notification::getLevel,level));
            result.put(level,count);total+=count;
        }
        result.put("total",total);return result;
    }
    @PutMapping("/{id}/acknowledge") public OperationsInboxStore.Acknowledgement acknowledge(@PathVariable long id,HttpServletRequest request){
        requireManager(request);return store.acknowledge(id,NotificationOwner.require(request));
    }
    @PutMapping("/acknowledge-all") public OperationsInboxStore.Acknowledgement acknowledgeBatch(HttpServletRequest request){
        requireManager(request);return store.acknowledgeBatch(NotificationOwner.require(request));
    }
    private void requireManager(HttpServletRequest request){
        NotificationOwner.require(request);permissions.requireMayManage(TrustedUser.require(request));
    }
}
