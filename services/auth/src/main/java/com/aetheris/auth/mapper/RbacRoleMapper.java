package com.aetheris.auth.mapper;

import com.aetheris.auth.entity.RbacRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface RbacRoleMapper extends BaseMapper<RbacRole> {

    @Select("SELECT p.code FROM auth.rbac_permission p " +
            "JOIN auth.rbac_role_permission rp ON rp.permission_id = p.id " +
            "WHERE rp.role_id = #{roleId}")
    List<String> selectPermissionCodes(@Param("roleId") Long roleId);

    @Delete("DELETE FROM auth.rbac_role_permission WHERE role_id = #{roleId}")
    int deletePermissions(@Param("roleId") Long roleId);

    @Insert("INSERT INTO auth.rbac_role_permission (role_id, permission_id) " +
            "SELECT #{roleId}, id FROM auth.rbac_permission WHERE code = #{code} " +
            "ON CONFLICT (role_id, permission_id) DO NOTHING")
    int insertPermission(@Param("roleId") Long roleId, @Param("code") String code);
}
