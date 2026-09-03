package com.aetheris.auth.service;

import com.aetheris.auth.entity.PermissionVersion;
import com.aetheris.auth.mapper.PermissionVersionMapper;
import org.springframework.stereotype.Service;

@Service
public class PermissionVersionService {

    private final PermissionVersionMapper mapper;

    public PermissionVersionService(PermissionVersionMapper mapper) {
        this.mapper = mapper;
    }

    public long current() {
        PermissionVersion row = mapper.selectById(1);
        return row == null || row.getVersion() == null ? 0L : row.getVersion();
    }

    /** 原子递增权限版本，返回新版本号。任何授权/角色/组成员变更后必须调用。 */
    public long bump() {
        Long next = mapper.bump();
        return next == null ? current() : next;
    }
}