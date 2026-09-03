package com.aetheris.auth.mapper;

import com.aetheris.auth.entity.RbacPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RbacPermissionMapper extends BaseMapper<RbacPermission> {

    /** 某资源类型在权限目录中登记的全部动作（授权校验用）。 */
    @Select("SELECT DISTINCT action FROM auth.rbac_permission WHERE resource_type = #{resourceType}")
    List<String> selectActions(@Param("resourceType") String resourceType);

    /** 权限目录中登记的全部资源类型（授权校验用）。 */
    @Select("SELECT DISTINCT resource_type FROM auth.rbac_permission")
    List<String> selectResourceTypes();
}
