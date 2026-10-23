package com.aetheris.ingestion.controller;

import com.aetheris.ingestion.security.ResourceAuthorization;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Reconciliation remains management-only and uses only the current verified subject. */
@Component
public class RecoveryAdminGate {
    private final ResourceAuthorization authorization;
    public RecoveryAdminGate(ResourceAuthorization authorization){this.authorization=authorization;}
    public void requireManage(String username) {
        String current=authorization.username();
        if(current==null || !current.equals(username) || !authorization.mayManage())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"人工对账需要平台管理权限");
    }
}
