package com.aetheris.auth.mapper;

import com.aetheris.auth.entity.ResourceGrant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ResourceGrantMapper extends BaseMapper<ResourceGrant> {

    /** 指定主体名下的全部启用授权（含具体范围），供决策引擎使用。 */
    @Select("SELECT * FROM auth.resource_grant WHERE grantee_type = #{granteeType} " +
            "AND grantee_id = #{granteeId} AND enabled = TRUE")
    List<ResourceGrant> selectEnabledFor(@Param("granteeType") String granteeType,
                                         @Param("granteeId") Long granteeId);
}
