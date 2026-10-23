package com.aetheris.system.security;

import com.aetheris.security.servlet.TrustedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Stable personal ownership, obtainable only from the filter-verified current USER. */
public final class NotificationOwner {
    private final long uid;
    private final String username;
    private NotificationOwner(TrustedUser user) { uid=user.proof().uid();username=user.proof().username(); }
    public static NotificationOwner require(HttpServletRequest request) {
        try { return new NotificationOwner(TrustedUser.require(request)); }
        catch(IllegalStateException absent) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Verified user required"); }
    }
    public long uid(){return uid;}
    public String username(){return username;}
    @Override public String toString(){return "NotificationOwner[uid="+uid+"]";}
}
