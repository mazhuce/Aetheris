package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * Spark/Iceberg 的 SHOW TABLES 与 getTables() 都不返回表注释，只能靠
     * DESCRIBE FORMATTED 的 Comment 行取。表注释是 NL2SQL 命中率的关键信息，
     * 这里锁定解析逻辑，防止后续重构把它改回 null。
     */
    @Test
    void extractsHiveTableCommentFromDescribeFormatted() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        // DESCRIBE FORMATTED 前面是列定义，Comment 行夹在表属性区。
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getString(1)).thenReturn("order_id", "# Detailed Table Information", "Comment");
        // getString(2) 只在 key 命中 Comment 时才被调用一次，因此只存根那一个值。
        when(resultSet.getString(2)).thenReturn("  ODS 订单主表，含脏数据  ");

        assertEquals(
                "ODS 订单主表，含脏数据",
                jdbcService.hiveTableComment(connection, "ods", "ods_order"));
    }

    @Test
    void returnsNullWhenHiveTableHasNoComment() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("order_id");

        assertNull(jdbcService.hiveTableComment(connection, "ods", "ods_order"));
    }

    /** DESCRIBE FORMATTED 失败（权限不足等）不应中断整轮采集。 */
    @Test
    void toleratesDescribeFormattedFailure() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenThrow(new SQLException("权限不足"));

        assertNull(jdbcService.hiveTableComment(connection, "ods", "ods_order"));
    }
}
