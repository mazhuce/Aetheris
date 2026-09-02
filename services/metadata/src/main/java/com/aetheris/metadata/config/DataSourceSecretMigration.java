package com.aetheris.metadata.config;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceSecretService;
import com.aetheris.metadata.service.DataSourceService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataSourceSecretMigration implements CommandLineRunner {

    private final DataSourceService dataSourceService;
    private final DataSourceSecretService secretService;

    public DataSourceSecretMigration(DataSourceService dataSourceService, DataSourceSecretService secretService) {
        this.dataSourceService = dataSourceService;
        this.secretService = secretService;
    }

    @Override
    public void run(String... args) {
        for (DataSource dataSource : dataSourceService.list()) {
            String password = dataSource.getPassword();
            if (password != null && !password.isEmpty() && !secretService.isEncrypted(password)) {
                dataSource.setPassword(secretService.encrypt(password));
                dataSourceService.updateById(dataSource);
            }
        }
    }
}
