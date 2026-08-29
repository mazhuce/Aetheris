package com.aetheris.workflow.mapper;

import com.aetheris.workflow.entity.TaskInstance;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskInstanceMapper extends BaseMapper<TaskInstance> {
}
