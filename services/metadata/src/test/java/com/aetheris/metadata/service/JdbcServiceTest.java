package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcServiceTest {

    private final JdbcService jdbcService = new JdbcService();

    @Test
    void buildsHiveUrlWithAuthenticationParameters() {
        DataSource dataSource = source("hive2", "172.18.166.51", "default");
        dataSource.setConnectionParams("auth=noSasl");

        assertEquals(
                "jdbc:hive2://172.18.166.51:10000/default;auth=noSasl",
                jdbcService.buildUrl(dataSource, "hive2", 10000));
    }

    @Test
    void buildsCompatiblePostgresqlAndMysqlUrls() {
        assertEquals(
                "jdbc:postgresql://192.168.30.201:26257/defaultdb",
                jdbcService.buildUrl(source("cockroachdb", "192.168.30.201", "defaultdb"), "cockroachdb", 26257));
        assertEquals(
                "jdbc:mysql://192.168.30.202:4000/analytics?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                jdbcService.buildUrl(source("tidb", "192.168.30.202", "analytics"), "tidb", 4000));
    }

    @Test
    void rejectsJdbcUrlThatDoesNotMatchProtocolFamily() {
        DataSource dataSource = source("hive2", "localhost", "default");
        dataSource.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");

        assertThrows(IllegalArgumentException.class, () -> jdbcService.buildUrl(dataSource, "hive2", 10000));
    }

    private DataSource source(String type, String host, String database) {
        DataSource dataSource = new DataSource();
        dataSource.setType(type);
        dataSource.setHost(host);
        dataSource.setDatabaseName(database);
        return dataSource;
    }
}
