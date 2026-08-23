package com.aetheris.ingestion.service.impl;

import com.aetheris.ingestion.entity.IngestionTask;
import com.aetheris.ingestion.mapper.IngestionTaskMapper;
import com.aetheris.ingestion.service.IngestionTaskService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class IngestionTaskServiceImpl extends ServiceImpl<IngestionTaskMapper, IngestionTask>
        implements IngestionTaskService {
}
