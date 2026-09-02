package com.aetheris.ingestion.service.impl;

import com.aetheris.ingestion.entity.IngestionRun;
import com.aetheris.ingestion.mapper.IngestionRunMapper;
import com.aetheris.ingestion.service.IngestionRunService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class IngestionRunServiceImpl extends ServiceImpl<IngestionRunMapper, IngestionRun>
        implements IngestionRunService {
}
