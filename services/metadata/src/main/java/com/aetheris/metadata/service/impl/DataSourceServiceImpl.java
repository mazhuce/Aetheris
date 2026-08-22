package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.mapper.DataSourceMapper;
import com.aetheris.metadata.service.DataSourceService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class DataSourceServiceImpl extends ServiceImpl<DataSourceMapper, DataSource>
        implements DataSourceService {
}
