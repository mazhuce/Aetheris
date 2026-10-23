package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration entrypoint. Its wrapper provisions a disposable loopback PostgreSQL. */
class PostgresSqlSafetyIT {
    @Test
    void verifiesMigrationsLeastPrivilegeAndActualReadWriteBoundaries() throws Exception {
        String url = System.getenv("AETHERIS_TEST_POSTGRES_URL");
        String password = System.getenv("AETHERIS_TEST_POSTGRES_PASSWORD");
        assertNotNull(url, "Run scripts/audit-sql-boundary-acceptance.ps1 to provision an isolated database");
        URI target = URI.create(url.substring("jdbc:".length()));
        assertEquals("127.0.0.1", target.getHost());
        assertEquals("/aetheris_audit", target.getPath());
        assertNotNull(password);
        assertTrue(password.matches("[a-zA-Z0-9_-]{24,}"));

        Path migrations = Path.of("src/main/resources/db/migration").toAbsolutePath();
        Flyway flyway = Flyway.configure().dataSource(url, "audit_admin", password)
                .schemas("metadata").locations("filesystem:" + migrations).load();
        assertTrue(flyway.migrate().migrationsExecuted > 0);
        assertTrue(flyway.validateWithResult().validationSuccessful);
        assertEquals(0, flyway.info().pending().length);

        try (Connection admin = DriverManager.getConnection(url, "audit_admin", password)) {
            var sql = admin.createStatement();
            sql.execute("CREATE SCHEMA auth; CREATE TABLE auth.secret(value TEXT); INSERT INTO auth.secret VALUES ('private')");
            sql.execute("CREATE SCHEMA warehouse; CREATE TABLE warehouse.input(id INT PRIMARY KEY); INSERT INTO warehouse.input VALUES (1)");
            sql.execute("CREATE TABLE warehouse.output(id INT PRIMARY KEY)");
            sql.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
            sql.execute("CREATE ROLE audit_reader LOGIN PASSWORD '" + password + "'");
            sql.execute("CREATE ROLE audit_writer LOGIN PASSWORD '" + password + "'");
            sql.execute("GRANT USAGE ON SCHEMA warehouse TO audit_reader, audit_writer");
            sql.execute("GRANT SELECT ON ALL TABLES IN SCHEMA warehouse TO audit_reader, audit_writer");
            sql.execute("GRANT INSERT ON warehouse.output TO audit_writer");
            sql.execute("ALTER ROLE audit_reader SET search_path = pg_catalog, warehouse");

            DataSource reader = source(target, "audit_reader", password);
            DataSource writer = source(target, "audit_writer", password);
            // The same business source now has independent READ/WRITE credentials.
            reader.setWriteUsername("audit_writer"); reader.setWritePassword(password);
            JdbcService jdbc = new JdbcService();
            Map<String, Object> rows = jdbc.query(reader, "SELECT id FROM warehouse.input");
            assertEquals(1, rows.get("rowCount"));
            assertThrows(IllegalStateException.class, () -> jdbc.query(reader, "SELECT * FROM auth.secret"));
            assertThrows(IllegalStateException.class, () -> jdbc.query(writer, "SELECT * FROM metadata.data_source"));
            for (String attack : java.util.List.of(
                    "EXPLAIN ANALYZE\nDELETE FROM warehouse.input",
                    "WITH x AS (DELETE FROM warehouse.input RETURNING *) SELECT * FROM x",
                    "SELECT pg_sleep(1)", "SELECT dblink_connect('host=invalid')",
                    "SELECT 1 INTO OUTFILE '/tmp/never-created'")) {
                assertThrows(IllegalArgumentException.class, () -> jdbc.query(reader, attack));
            }
            // OUTPUT is a SQL Server parser keyword; quote the identifier in cross-dialect SQL.
            assertEquals(true, jdbc.execute(reader, "INSERT INTO warehouse.\"output\" SELECT id FROM warehouse.input", 5).get("success"));
            assertEquals(1, jdbc.query(reader, "SELECT * FROM warehouse.\"output\"").get("rowCount"));

            verifiesDisabledSourcesNeverReachTheActualDriver(url, reader, jdbc);

            try (Connection read = DriverManager.getConnection(url, "audit_reader", password)) {
                SQLException denied = assertThrows(SQLException.class,
                        () -> read.createStatement().execute("DELETE FROM warehouse.input"));
                assertEquals("42501", denied.getSQLState());
            }
            // A role that can INSERT is still prevented from writing within a read transaction.
            try (Connection read = DriverManager.getConnection(url, "audit_writer", password)) {
                read.setReadOnly(true); read.setAutoCommit(false);
                SQLException denied = assertThrows(SQLException.class,
                        () -> read.createStatement().execute("INSERT INTO warehouse.output VALUES (2)"));
                assertEquals("25006", denied.getSQLState());
                read.rollback();
            }
            try (var count = sql.executeQuery("SELECT COUNT(*) FROM warehouse.input")) {
                count.next(); assertEquals(1, count.getInt(1));
            }
        }
    }

    private void verifiesDisabledSourcesNeverReachTheActualDriver(String url, DataSource reader, JdbcService jdbc) throws Exception {
        var delegate = DriverManager.getDriver(url);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var counted = new CountingDriver(delegate, calls);
        DriverManager.deregisterDriver(delegate);
        DriverManager.registerDriver(counted);
        try {
            assertEquals(1, jdbc.query(reader, "SELECT id FROM warehouse.input").get("rowCount"));
            assertEquals(1, calls.get(), "The wrapper must observe a real PostgreSQL connection");
            for (String state : java.util.Arrays.asList(null, "", "inactive", "disabled", "isolated", "unknown", "ACTIVE", "active ")) {
                reader.setStatus(state);
                assertThrows(IllegalArgumentException.class, () -> jdbc.query(reader, "SELECT id FROM warehouse.input"));
                assertThrows(IllegalArgumentException.class, () -> jdbc.execute(reader,
                        "INSERT INTO warehouse.\"output\" VALUES (2)", 5));
                assertThrows(IllegalArgumentException.class, () -> jdbc.probe(reader, 1));
                assertEquals(1, calls.get(), "A non-active source must not create any physical connection");
            }
            reader.setStatus("active");
            assertEquals(1, jdbc.query(reader, "SELECT id FROM warehouse.input").get("rowCount"));
            assertEquals(2, calls.get(), "Re-enabling preserves the legitimate query path");
        } finally {
            reader.setStatus("active"); DriverManager.deregisterDriver(counted); DriverManager.registerDriver(delegate);
        }
    }

    private record CountingDriver(java.sql.Driver delegate, java.util.concurrent.atomic.AtomicInteger calls) implements java.sql.Driver {
        public Connection connect(String url, java.util.Properties properties) throws SQLException {
            if (!acceptsURL(url)) return null;
            calls.incrementAndGet(); return delegate.connect(url, properties);
        }
        public boolean acceptsURL(String url) throws SQLException { return delegate.acceptsURL(url); }
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties properties) throws SQLException { return delegate.getPropertyInfo(url, properties); }
        public int getMajorVersion() { return delegate.getMajorVersion(); }
        public int getMinorVersion() { return delegate.getMinorVersion(); }
        public boolean jdbcCompliant() { return delegate.jdbcCompliant(); }
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    }

    private DataSource source(URI target, String user, String password) {
        DataSource source = new DataSource();
        source.setType("postgresql"); source.setHost(target.getHost()); source.setPort(target.getPort());
        source.setStatus("active");
        source.setDatabaseName("aetheris_audit"); source.setUsername(user); source.setPassword(password);
        return source;
    }
}
