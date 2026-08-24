package com.aetheris.metadata.config;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class ManagedDataSourceInitializer implements CommandLineRunner {

    private final DataSourceService dataSourceService;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    public ManagedDataSourceInitializer(
            DataSourceService dataSourceService,
            @Value("${aetheris.managed-datasource.enabled:true}") boolean enabled,
            @Value("${DB_HOST:127.0.0.1}") String host,
            @Value("${DB_PORT:5433}") int port,
            @Value("${DB_NAME:aetheris}") String database,
            @Value("${DB_USER:aetheris}") String username,
            @Value("${DB_PASSWORD:aetheris}") String password) {
        this.dataSourceService = dataSourceService;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }
        DataSource dataSource = dataSourceService.getOne(
                new LambdaQueryWrapper<DataSource>().eq(DataSource::getManaged, true), false);
        if (dataSource == null) {
            dataSource = new DataSource();
            dataSource.setName("Aetheris PostgreSQL");
            dataSource.setType("postgresql");
            dataSource.setManaged(true);
        }
        dataSource.setHost(host);
        dataSource.setPort(port);
        dataSource.setDatabaseName(database);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setStatus("active");
        dataSource.setDescription("Aetheris 平台内部 PostgreSQL，由系统自动维护连接配置");
        if (dataSource.getId() == null) {
            dataSourceService.save(dataSource);
        } else {
            dataSourceService.updateById(dataSource);
        }
    }
}
