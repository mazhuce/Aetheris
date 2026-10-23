package com.aetheris.metadata.config;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/** Quarantine legacy platform connections without deleting records or replacing credentials. */
@Component
public class ManagedDataSourceInitializer implements CommandLineRunner {
    private final DataSourceService dataSourceService;

    public ManagedDataSourceInitializer(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @Override
    public void run(String... args) {
        for (DataSource source : dataSourceService.list(
                new LambdaQueryWrapper<DataSource>().eq(DataSource::getManaged, true))) {
            if (!"isolated".equals(source.getStatus())) {
                source.setStatus("isolated");
                if (!dataSourceService.updateById(source)) {
                    throw new IllegalStateException("平台内部数据源隔离状态更新失败");
                }
            }
        }
    }
}
