package com.aetheris.auth.mapper;

import com.aetheris.auth.entity.PermissionVersion;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface PermissionVersionMapper extends BaseMapper<PermissionVersion> {

    /** 原子递增权限版本并返回新版本号，供授权变更后通知下游缓存失效。 */
    @Select("UPDATE auth.permission_version SET version = version + 1, updated_at = now() " +
            "WHERE id = 1 RETURNING version")
    Long bump();
}
