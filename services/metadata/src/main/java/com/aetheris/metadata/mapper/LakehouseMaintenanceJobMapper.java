package com.aetheris.metadata.mapper;

import com.aetheris.metadata.entity.LakehouseMaintenanceJob;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LakehouseMaintenanceJobMapper extends BaseMapper<LakehouseMaintenanceJob> {
    @Update("UPDATE lakehouse_maintenance_job SET status='running', started_at=now(), updated_at=now() "
            + "WHERE id=#{id} AND status='requested'")
    int claimRequested(@Param("id") Long id);
}
