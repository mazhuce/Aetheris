package com.aetheris.system.security;

import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.servlet.TrustedUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.concurrent.TimeUnit;

/** Only the current verified USER can ask auth for its own management permission. */
@Component
public class AuthPermissionClient {
    private final AuthProtocolClient auth;
    public AuthPermissionClient(AuthProtocolClient auth){this.auth=auth;}
    public boolean mayManage(String username) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))return false;
        final TrustedUser user;
        try {user=TrustedUser.require(attributes.getRequest());}
        catch(IllegalStateException missing){return false;}
        if(username==null||!username.equals(user.proof().username()))return false;
        java.util.concurrent.CompletableFuture<AuthProtocolClient.Decision> pending=null;
        try {pending=auth.mayManage(user.compactProof());return pending.get(2500,TimeUnit.MILLISECONDS).allowed();}
        catch(Exception failure){if(pending!=null)pending.cancel(true);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();return false;}
    }
    /** Operations endpoints distinguish denial from unavailable/untrusted auth, with no personal fallback. */
    public void requireMayManage(TrustedUser user) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Verified user required");
        final TrustedUser current;
        try{current=TrustedUser.require(attributes.getRequest());}
        catch(IllegalStateException absent){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Verified user required");}
        if(user==null||!current.compactProof().equals(user.compactProof()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED,"Verified user required");
        java.util.concurrent.CompletableFuture<AuthProtocolClient.Decision> pending=null;
        final AuthProtocolClient.Decision decision;
        try{pending=auth.mayManage(user.compactProof());decision=pending.get(2500,TimeUnit.MILLISECONDS);}
        catch(Exception unavailable){
            if(pending!=null)pending.cancel(true);if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Permission service unavailable");
        }
        if(decision==null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Permission service unavailable");
        if(!decision.allowed())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"Operations management permission required");
    }
}
