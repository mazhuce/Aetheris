package com.aetheris.quality.controller;

import com.aetheris.quality.security.ResourceAuthorization;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The controller name must exactly match the current verified proof; no subject substitution. */
@Component
public class RecoveryAdminGate {
    private final ResourceAuthorization authorization;
    public RecoveryAdminGate(ResourceAuthorization authorization){this.authorization=java.util.Objects.requireNonNull(authorization);}
    public void requireManage(String username){
        var user=authorization.currentUser();
        if(!user.proof().username().equals(username)||!authorization.mayManage())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"人工对账需要当前用户的平台管理权限");
    }
}
