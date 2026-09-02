package com.aetheris.metadata.service.impl;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.mapper.DataSourceMapper;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.DataSourceSecretService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class DataSourceServiceImpl extends ServiceImpl<DataSourceMapper, DataSource>
        implements DataSourceService {

    private final DataSourceSecretService secretService;

    public DataSourceServiceImpl(DataSourceSecretService secretService) {
        this.secretService = secretService;
    }

    @Override
    public boolean save(DataSource entity) {
        protect(entity);
        return super.save(entity);
    }

    @Override
    public boolean updateById(DataSource entity) {
        protect(entity);
        return super.updateById(entity);
    }

    private void protect(DataSource entity) {
        if (entity != null) {
            entity.setPassword(secretService.encrypt(entity.getPassword()));
        }
    }
}
