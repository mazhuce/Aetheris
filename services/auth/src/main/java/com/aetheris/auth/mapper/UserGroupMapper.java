package com.aetheris.auth.mapper;

import com.aetheris.auth.entity.UserGroup;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserGroupMapper extends BaseMapper<UserGroup> {

    @Select("SELECT group_id FROM auth.user_group_member WHERE user_id = #{userId}")
    List<Long> selectGroupIdsOfUser(@Param("userId") Long userId);

    @Select("SELECT gm.user_id FROM auth.user_group_member gm WHERE gm.group_id = #{groupId}")
    List<Long> selectMemberIds(@Param("groupId") Long groupId);

    @Insert("INSERT INTO auth.user_group_member (group_id, user_id) VALUES (#{groupId}, #{userId}) " +
            "ON CONFLICT (group_id, user_id) DO NOTHING")
    int insertMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Delete("DELETE FROM auth.user_group_member WHERE group_id = #{groupId} AND user_id = #{userId}")
    int deleteMember(@Param("groupId") Long groupId, @Param("userId") Long userId);

    @Delete("DELETE FROM auth.user_group_member WHERE group_id = #{groupId}")
    int deleteAllMembers(@Param("groupId") Long groupId);
}
