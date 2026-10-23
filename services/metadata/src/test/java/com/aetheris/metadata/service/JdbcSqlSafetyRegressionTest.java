package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Public execution entrypoints, rather than a copy of the guard's regular expressions. */
class JdbcSqlSafetyRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT\npg_sleep(1)", "SELECT\r\npg_read_file('/etc/passwd')",
            "EXPLAIN ANALYZE\nDELETE FROM safety_rows",
            "WITH x AS (\nDELETE FROM safety_rows RETURNING *) SELECT * FROM x",
            "SELECT 1 INTO OUTFILE '/tmp/no-file'", "SELECT * FROM safety_rows FOR UPDATE",
            "SELECT dblink_connect('host=invalid')", "SELECT query_to_xml('DELETE FROM safety_rows', true, false, '')",
            "SELECT \"pg_sleep\"(1)", "SELECT \"COU\"\"NT\"(1)", "SELECT public.count(*) FROM safety_rows",
            "SELECT \"PG_CATALOG\".count(1)", "SELECT \"COUNT\"(1)",
            "SELECT 危险函数(1)", "SELECT evil.危险函数(1)",
            "SELECT 1 /*! INTO OUTFILE '/tmp/no-file' */", "SELECT 1; SELECT 2"
    })
    void rejectsUnsafeQueriesBeforeConnecting(String sql) {
        JdbcService service = new JdbcService() {
            @Override Connection connect(DataSource source) { throw new AssertionError("Unsafe SQL reached JDBC"); }
        };
        assertThrows(IllegalArgumentException.class, () -> service.query(source(), sql));
    }

    @Test
    void realJdbcKeepsRowsIntactAndAllowsLiteralAndCommentSemicolons() throws Exception {
        DataSource source = source();
        try (Connection connection = DriverManager.getConnection("jdbc:h2:" + source.getDatabaseName())) {
            connection.createStatement().execute("CREATE TABLE safety_rows(id INT PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO safety_rows VALUES (1)");
            JdbcService service = new JdbcService();
            assertThrows(IllegalArgumentException.class,
                    () -> service.query(source, "EXPLAIN ANALYZE\nDELETE FROM safety_rows"));
            assertEquals(1, service.query(source, "-- DROP is documentation;\nSELECT 'DROP; -- text' AS note, id FROM safety_rows;").get("rowCount"));
            try (var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM safety_rows")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void legacyManagedPlatformConnectionIsUnavailableOnEveryExecutionPath() {
        DataSource source = source();
        source.setManaged(true);
        assertThrows(IllegalArgumentException.class, () -> new JdbcService().query(source, "SELECT 1"));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcService().execute(source, "INSERT INTO warehouse_t SELECT 1", 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mem:bad;INIT=CREATE ALIAS p AS 'x'", "mem:bad;init = RUNSCRIPT FROM 'x'"})
    void rejectsEmbeddedInitInGeneratedAndCustomUrls(String database) {
        DataSource source = source();
        source.setDatabaseName(database);
        assertThrows(IllegalArgumentException.class, () -> new JdbcService().buildUrl(source, "h2", 0));
        source.setJdbcUrl("jdbc:h2:" + database);
        assertThrows(IllegalArgumentException.class, () -> new JdbcService().buildUrl(source, "h2", 0));
    }

    @Test
    void rejectsParametersInjectedThroughStructuredDatabaseNameOrHost() {
        DataSource source = source();
        source.setType("postgresql");
        source.setDatabaseName("db?socketFactory=not.allowed.Factory");
        assertThrows(IllegalArgumentException.class, () -> new JdbcService().buildUrl(source, "postgresql", 5432));
        source.setDatabaseName("db");
        source.setHost("localhost/db?socketFactory=not.allowed.Factory");
        assertThrows(IllegalArgumentException.class, () -> new JdbcService().buildUrl(source, "postgresql", 5432));
    }

    private DataSource source() {
        DataSource source = new DataSource();
        source.setType("h2");
        source.setStatus("active");
        source.setHost("embedded");
        source.setDatabaseName("mem:safety_" + UUID.randomUUID().toString().replace("-", ""));
        return source;
    }
}
