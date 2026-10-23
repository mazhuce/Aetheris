package com.aetheris.metadata.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

class JdbcErrorPrivacyTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 2})
    void driverDetailsStayOutOfReadWriteAndTransferLogs(int operation) {
        var jdbc = new JdbcService() {
            @Override Connection connect(DataSource source) throws SQLException { throw failure(); }
            @Override Connection connectWrite(DataSource source) throws SQLException { throw failure(); }
            private SQLException failure() {
                return new SQLException("password=driver-log-canary; SELECT 'private-query'", "state-canary", 123);
            }
        };
        var source = new DataSource(); source.setId(7L); source.setStatus("active");
        source.setType("postgresql"); source.setHost("host-canary"); source.setDatabaseName("db-canary");
        source.setUsername("reader"); source.setPassword("reader-fixture");
        source.setWriteUsername("writer"); source.setWritePassword("writer-fixture");
        var logger = (Logger) LoggerFactory.getLogger(JdbcService.class);
        var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
        try {
            var error = assertThrows(IllegalStateException.class, () -> {
                switch (operation) {
                    case 0 -> jdbc.query(source, "SELECT 1");
                    case 1 -> jdbc.execute(source, "INSERT INTO business.output_table(v) VALUES (1)", 5);
                    default -> jdbc.transfer(source, source, "SELECT 1 AS v", "business.output_table", "append", 1);
                }
            });
            assertFalse(error.getMessage().contains("canary"));
            assertFalse(appender.list.isEmpty());
            for (var entry : appender.list) {
                assertNull(entry.getThrowableProxy(), "Raw driver stack traces can contain URL, credentials and SQL");
                assertFalse(entry.getFormattedMessage().contains("canary"));
            }
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
