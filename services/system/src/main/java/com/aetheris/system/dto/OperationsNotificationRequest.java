package com.aetheris.system.dto;

import java.util.Set;
import java.util.UUID;

/** No recipient/source authority is accepted from a producer's JSON body. */
public record OperationsNotificationRequest(UUID eventId,Long sourceId,String level,String title,String message,String linkPath) {
    public OperationsNotificationRequest {
        if(eventId==null || sourceId!=null&&sourceId<=0 || level==null||!Set.of("info","warning","critical").contains(level)
                || title==null||title.isBlank()||title.length()>256 || message!=null&&message.length()>1024
                || linkPath!=null&&(linkPath.length()>256||!linkPath.matches("/(?:[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*)?")))
            throw new IllegalArgumentException("Invalid operations event");
    }
}
