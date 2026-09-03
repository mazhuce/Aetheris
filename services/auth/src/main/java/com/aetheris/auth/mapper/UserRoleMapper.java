package com.aetheris.auth.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserRoleMapper {

    @Select("SELECT role_id FROM auth.user_role WHERE user_id = #{userId}")
    List<Long> selectRoleIdsOfUser(@Param("userId") Long userId);

    @Select("SELECT r.code FROM auth.user_role ur JOIN auth.rbac_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId}")
    List<String> selectRoleCodesOfUser(@Param("userId") Long userId);

    @Insert("INSERT INTO auth.user_role (user_id, role_id) VALUES (#{userId}, #{roleId}) " +
            "ON CONFLICT (user_id, role_id) DO NOTHING")
    int insert(@Param("userId") Long userId, @Param("roleId") Long roleId);

    @Delete("DELETE FROM auth.user_role WHERE user_id = #{userId}")
    int deleteAllOfUser(@Param("userId") Long userId);

    @Delete("DELETE FROM auth.user_role WHERE role_id = #{roleId}")
    int deleteAllOfRole(@Param("roleId") Long roleId);
}
