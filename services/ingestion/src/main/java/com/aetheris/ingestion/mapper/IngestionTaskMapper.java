package com.aetheris.ingestion.mapper;

import com.aetheris.ingestion.entity.IngestionTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IngestionTaskMapper extends BaseMapper<IngestionTask> {
}
