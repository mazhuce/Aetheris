package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class JdbcServiceTest {

    private final JdbcService jdbcService = new JdbcService();

    @Test
    void healthProbeExecutesOnlySelectOneWithTimeoutAndClosesResources() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getInt(1)).thenReturn(1);
        when(connection.getMetaData()).thenThrow(new SQLException("Unsupported"));
        JdbcService service = new JdbcService() {
            @Override Connection connect(DataSource dataSource) { return connection; }
        };

        service.probe(source("spark", "relay", "default"), 10);

        verify(statement).setQueryTimeout(10);
        verify(statement).executeQuery("SELECT 1");
        verify(statement).close();
        verify(rows).close();
        verify(connection).close();
    }

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
    void buildsExpandedEnterpriseAndAnalyticsUrls() {
        assertEquals("jdbc:sqlserver://sqlserver:1433;databaseName=master;encrypt=false",
                jdbcService.buildUrl(source("sqlserver", "sqlserver", "master"), "sqlserver", 1433));
        assertEquals("jdbc:ch://clickhouse:8123/analytics?compress=0",
                jdbcService.buildUrl(source("clickhouse", "clickhouse", "analytics"), "clickhouse", 8123));
        assertEquals("jdbc:trino://trino:8080/hive",
                jdbcService.buildUrl(source("trino", "trino", "hive"), "trino", 8080));
        assertEquals("jdbc:oracle:thin:@//oracle:1521/ORCL",
                jdbcService.buildUrl(source("oracle", "oracle", "ORCL"), "oracle", 1521));
        assertEquals("jdbc:sqlite:aetheris.db",
                jdbcService.buildUrl(source("sqlite", "", "aetheris.db"), "sqlite", 0));
    }

    @ParameterizedTest(name = "runtime JDBC driver is packaged: {0}")
    @ValueSource(strings = {
            "org.postgresql.Driver",
            "com.mysql.cj.jdbc.Driver",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "com.clickhouse.jdbc.ClickHouseDriver",
            "oracle.jdbc.OracleDriver",
            "org.sqlite.JDBC",
            "org.apache.hive.jdbc.HiveDriver",
            "io.trino.jdbc.TrinoDriver"
    })
    void packagesSupportedJdbcDriversForManagedConnections(String driverClass) {
        assertDoesNotThrow(() -> Class.forName(driverClass));
    }

    @Test
    void executesARealReadOnlyQueryThroughTheSqliteDriver() {
        DataSource dataSource = source("sqlite", "", ":memory:");

        var result = jdbcService.query(dataSource, "SELECT 1 AS value");

        assertEquals(List.of("value"), result.get("columns"));
        assertEquals(List.of(List.of(1)), result.get("rows"));
        assertEquals(1, result.get("rowCount"));
    }

    @Test
    void validatesExpandedCustomJdbcProtocols() {
        DataSource dataSource = source("clickhouse", "localhost", "default");
        dataSource.setJdbcUrl("jdbc:postgresql://localhost:5432/postgres");
        assertThrows(IllegalArgumentException.class,
                () -> jdbcService.buildUrl(dataSource, "clickhouse", 8123));

        dataSource.setJdbcUrl("jdbc:ch://localhost:8123/default");
        assertEquals("jdbc:ch://localhost:8123/default", jdbcService.buildUrl(dataSource, "clickhouse", 8123));
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

    @Test
    void transfersQueryRowsWithTransactionalBatchInsert() throws Exception {
        DataSource source = source("postgresql", "source", "app");
        DataSource target = source("postgresql", "target", "warehouse");
        Connection sourceConnection = mock(Connection.class);
        Connection targetConnection = mock(Connection.class);
        Statement query = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        PreparedStatement insert = mock(PreparedStatement.class);

        when(sourceConnection.createStatement()).thenReturn(query);
        when(query.executeQuery("SELECT id, name FROM orders")).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(metadata.getColumnLabel(2)).thenReturn("name");
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getObject(1)).thenReturn(1L, 2L);
        when(rows.getObject(2)).thenReturn("A", "B");
        when(targetConnection.prepareStatement(
                "INSERT INTO \"ods\".\"orders\" (\"id\", \"name\") VALUES (?, ?)"))
                .thenReturn(insert);

        JdbcService service = new JdbcService() {
            @Override
            Connection connect(DataSource dataSource) {
                return dataSource == source ? sourceConnection : targetConnection;
            }
        };

        var result = service.transfer(
                source, target, "SELECT id, name FROM orders", "ods.orders", "append", 100);

        assertEquals(2L, result.get("rowsWritten"));
        verify(sourceConnection).setReadOnly(true);
        verify(targetConnection).setAutoCommit(false);
        verify(insert, times(2)).addBatch();
        verify(insert).executeBatch();
        verify(targetConnection).commit();
    }

    @Test
    void transfersFromSparkWithoutUnsupportedReadOnlyHint() throws Exception {
        DataSource source = source("spark", "source", "default");
        DataSource target = source("postgresql", "target", "warehouse");
        Connection sourceConnection = mock(Connection.class);
        Connection targetConnection = mock(Connection.class);
        Statement query = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        PreparedStatement insert = mock(PreparedStatement.class);

        when(sourceConnection.createStatement()).thenReturn(query);
        when(query.executeQuery("SELECT id FROM orders")).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnLabel(1)).thenReturn("id");
        when(rows.next()).thenReturn(false);
        when(targetConnection.prepareStatement(
                "INSERT INTO \"ods\".\"orders\" (\"id\") VALUES (?)"))
                .thenReturn(insert);

        JdbcService service = new JdbcService() {
            @Override
            Connection connect(DataSource dataSource) {
                return dataSource == source ? sourceConnection : targetConnection;
            }
        };

        var result = service.transfer(
                source, target, "SELECT id FROM orders", "ods.orders", "append", 100);

        assertEquals(0L, result.get("rowsWritten"));
        verify(sourceConnection, never()).setReadOnly(true);
        verify(targetConnection).commit();
    }

    @Test
    void transferRejectsUnsafeTargetIdentifier() {
        DataSource source = source("postgresql", "source", "app");
        DataSource target = source("postgresql", "target", "warehouse");

        assertThrows(IllegalArgumentException.class, () -> jdbcService.transfer(
                source, target, "SELECT id FROM orders", "ods.orders; DROP TABLE users", "append", 100));
    }
}
