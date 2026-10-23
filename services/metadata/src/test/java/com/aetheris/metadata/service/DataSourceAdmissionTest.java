package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceAdmissionTest {
    static Stream<String> nonActive() {
        return Arrays.asList(null, "", "inactive", "disabled", "isolated", "unknown", "ACTIVE", "active ").stream();
    }

    static DataSource source() {
        var source = new DataSource();
        source.setId(7L); source.setType("h2"); source.setHost("embedded");
        source.setDatabaseName("mem:admission-fixture"); source.setUsername("reader");
        source.setPassword("fixture-reader"); source.setWriteUsername("writer");
        source.setWritePassword("fixture-writer"); source.setStatus("active");
        return source;
    }

    @ParameterizedTest @MethodSource("nonActive")
    void everyBusinessConnectionRejectsNonActiveBeforeDriver(String status) throws Exception {
        var source = source(); source.setStatus(status); var jdbc = new JdbcService();
        try (var driver = mockStatic(DriverManager.class)) {
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.connect(source)),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.connectWrite(source)),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.query(source, "SELECT 1")),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.probe(source, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.listSchemas(source)),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.listTables(source, "PUBLIC")),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.listColumns(source, "PUBLIC", "fixture")),
                    () -> assertThrows(IllegalArgumentException.class, () -> jdbc.preview(source, "PUBLIC", "fixture")));
            driver.verifyNoInteractions();
        }
    }

    @Test void activeSourceStillReachesRealEmbeddedDatabase() {
        var source = source(); source.setDatabaseName("mem:active-admission-" + java.util.UUID.randomUUID());
        var result = new JdbcService().query(source, "SELECT 1 AS admission_value");
        assertEquals(1, result.get("rowCount"));
    }

    @Test void managedActiveSourceNeverCreatesAConnection() throws Exception {
        var source = source(); source.setManaged(true);
        try (var driver = mockStatic(DriverManager.class)) {
            assertThrows(IllegalArgumentException.class, () -> new JdbcService().connect(source));
            driver.verifyNoInteractions();
        }
    }
}
