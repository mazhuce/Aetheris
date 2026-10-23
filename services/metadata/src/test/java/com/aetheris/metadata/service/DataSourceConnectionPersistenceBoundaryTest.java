package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.mapper.DataSourceMapper;
import com.aetheris.metadata.service.impl.DataSourceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataSourceConnectionPersistenceBoundaryTest {
    private final DataSourceMapper mapper = mock(DataSourceMapper.class);
    private final DataSourceServiceImpl service = new DataSourceServiceImpl(
            new DataSourceSecretService(Base64.getEncoder().encodeToString(new byte[32])));
    @BeforeEach void setup() { ReflectionTestUtils.setField(service, "baseMapper", mapper); when(mapper.insert(any(DataSource.class))).thenReturn(1); when(mapper.update(any(DataSource.class), any())).thenReturn(1); }

    @ParameterizedTest @ValueSource(strings = {
            "jdbc:postgresql://reader:secret-canary@db.example/business", "jdbc:postgresql://db.example/business?password=secret-canary",
            "jdbc:postgresql://db.example/business?options=-csecret-canary", "jdbc:postgresql://db.example/business?%70assword=secret-canary",
            "jdbc:mysql://db.example/business", "jdbc:postgresql://db.example/business#secret-canary"})
    void invalidCustomUrlsNeverReachPersistence(String url) {
        DataSource source = source(); source.setJdbcUrl(url);
        var error = assertThrows(IllegalArgumentException.class, () -> service.save(source));
        assertFalse(error.getMessage().contains("secret-canary"));
        verify(mapper, never()).insert(any(DataSource.class));
    }

    @ParameterizedTest @ValueSource(strings = {"password=secret-canary", "user=secret-canary", "INIT=secret-canary",
            "unknown-secret-canary=value", "socketFactory=secret-canary", "%2570assword=secret-canary"})
    void unsafeParametersNeverReachPersistence(String params) {
        DataSource source = source(); source.setConnectionParams(params);
        var error = assertThrows(IllegalArgumentException.class, () -> service.save(source));
        assertFalse(error.getMessage().contains("secret-canary"));
        verify(mapper, never()).insert(any(DataSource.class));
    }

    @Test void unrelatedAndIsolationUpdatesCanPreserveHistoricOptionsWithoutChangingThem() {
        DataSource old = source(); old.setId(7L); old.setConnectionParams("password=secret-canary");
        when(mapper.selectById(7L)).thenReturn(old);
        for (String status : new String[]{null, "isolated"}) {
            DataSource patch = new DataSource(); patch.setId(7L); patch.setDescription("ordinary edit"); patch.setStatus(status);
            assertTrue(service.updateById(patch));
            assertNull(patch.getConnectionParams(), "Do not copy historic options into the patch");
            assertEquals("password=secret-canary", old.getConnectionParams());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"type", "host", "port", "database", "url", "reactivate"})
    void changingAnyConnectionOrReactivatingRequiresSafeMergedOptions(String change) {
        DataSource old = source(); old.setId(7L); old.setStatus("inactive"); old.setConnectionParams("password=secret-canary");
        when(mapper.selectById(7L)).thenReturn(old);
        DataSource patch = new DataSource(); patch.setId(7L);
        switch(change) {
            case "type" -> patch.setType("mysql"); case "host" -> patch.setHost("another.example");
            case "port" -> patch.setPort(5440); case "database" -> patch.setDatabaseName("another");
            case "url" -> patch.setJdbcUrl("jdbc:postgresql://db.example/business"); case "reactivate" -> patch.setStatus("active");
        }
        assertThrows(IllegalArgumentException.class, () -> service.updateById(patch));
        verify(mapper, never()).update(any(DataSource.class), any());
    }

    @Test void explicitSafeReplacementAndEmptyStringClearAreAllowed() {
        DataSource old = source(); old.setId(7L); old.setStatus("inactive"); old.setJdbcUrl("jdbc:postgresql://reader:secret-canary@db.example/business"); old.setConnectionParams("password=secret-canary");
        when(mapper.selectById(7L)).thenReturn(old);
        for (boolean clear : new boolean[]{false, true}) {
            DataSource patch = new DataSource(); patch.setId(7L); patch.setStatus("active");
            patch.setJdbcUrl(clear ? "" : "jdbc:postgresql://db.example/business?sslmode=verify-full");
            patch.setConnectionParams(clear ? "" : "connectTimeout=5");
            assertTrue(service.updateById(patch));
            assertEquals(clear ? "" : "connectTimeout=5", patch.getConnectionParams());
        }
    }

    @Test void existingSafeEngineFamiliesAndParametersStillPersistWithoutOpeningConnections() {
        for (var definition : DataSourceTypeCatalog.all()) {
            DataSource source = source(); source.setType(definition.type()); source.setPort(definition.defaultPort());
            source.setDatabaseName(definition.defaultDatabase());
            if (java.util.Set.of("hive2", "spark").contains(definition.type())) source.setConnectionParams("auth=noSasl");
            assertTrue(service.save(source), definition.type());
        }
    }

    @Test void knownCustomNetworkAndEmbeddedUrlsRemainValid() {
        String[][] sources = {
                {"postgresql", "jdbc:postgresql://db.example:5432/business?sslmode=verify-full"},
                {"mysql", "jdbc:mysql://db.example:3306/business?sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai"},
                {"hive2", "jdbc:hive2://db.example:10000/default;auth=noSasl"},
                {"spark", "jdbc:hive2://db.example:10000/default;auth=noSasl"},
                {"sqlserver", "jdbc:sqlserver://db.example:1433;databaseName=business;encrypt=true;trustServerCertificate=false"},
                {"oracle", "jdbc:oracle:thin:@//db.example:1521/ORCL"},
                {"clickhouse", "jdbc:ch://db.example:8123/default?compress=0"},
                {"trino", "jdbc:trino://db.example:8080/hive"},
                {"h2", "jdbc:h2:mem:fixture;MODE=PostgreSQL"},
                {"sqlite", "jdbc:sqlite:fixture.db"}, {"derby", "jdbc:derby:memory:fixture;create=true"}};
        for (String[] item : sources) {
            DataSource source = source(); source.setType(item[0]); source.setJdbcUrl(item[1]);
            assertTrue(service.save(source), item[0]);
            assertFalse(DataSourceConnectionInputValidator.hasUnsafeConnectionOptions(source), item[0]);
        }
    }

    @Test void invalidUnchangedOptionsAreNotValidatedByClientControlledManagedOrRevisionFlags() {
        DataSource old = source(); old.setId(7L); old.setConnectionRevision(9L); old.setConnectionParams("password=secret-canary");
        when(mapper.selectById(7L)).thenReturn(old);
        DataSource patch = source(); patch.setId(7L); patch.setManaged(true); patch.setConnectionRevision(9L);
        patch.setJdbcUrl("jdbc:postgresql://db.example/business");
        assertThrows(IllegalArgumentException.class, () -> service.updateById(patch));
        verify(mapper, never()).update(any(DataSource.class), any());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("embeddedSecretDatabases")
    void embeddedDatabaseNamesCannotSmuggleDriverProperties(String type, String database) {
        DataSource source = source(); source.setType(type); source.setHost("embedded"); source.setDatabaseName(database);
        var error = assertThrows(IllegalArgumentException.class, () -> service.save(source));
        assertFalse(error.getMessage().contains("secret-canary"));
        verify(mapper, never()).insert(any(DataSource.class));
    }

    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> embeddedSecretDatabases() {
        return java.util.stream.Stream.of("h2", "sqlite", "derby").flatMap(type ->
                java.util.stream.Stream.of("INIT=secret-canary", "USER=secret-canary", "PASSWORD=secret-canary")
                        .map(property -> org.junit.jupiter.params.provider.Arguments.of(type,
                                (type.equals("sqlite") ? "fixture.db" : "mem:fixture") + ";" + property)));
    }

    @Test void legalEmbeddedMemoryFileAndReadOnlyModesRemainStorageCompatible() {
        String[][] definitions = {{"h2", "mem:fixture;MODE=PostgreSQL"}, {"h2", "file:C:/aetheris-fixture/test;DB_CLOSE_ON_EXIT=FALSE"},
                {"sqlite", "fixture.db"}, {"sqlite", "file:fixture?mode=memory"},
                {"derby", "memory:fixture;create=true"}, {"derby", "C:/aetheris-fixture/test;create=true"}};
        for (String[] item : definitions) {
            DataSource source = source(); source.setType(item[0]); source.setHost("embedded"); source.setDatabaseName(item[1]);
            assertTrue(service.save(source), item[0] + ":" + item[1]);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"host", "database"})
    void malformedStructuredConnectionFieldsCannotPersistCredentials(String field) {
        DataSource source = source();
        if (field.equals("host")) source.setHost("reader:secret-canary@db.example");
        else source.setDatabaseName("business?password=secret-canary");
        var error = assertThrows(IllegalArgumentException.class, () -> service.save(source));
        assertFalse(error.getMessage().contains("secret-canary"));
        verify(mapper, never()).insert(any(DataSource.class));
    }

    private static DataSource source() {
        DataSource source = new DataSource(); source.setName("business"); source.setType("postgresql");
        source.setHost("db.example"); source.setPort(5432); source.setDatabaseName("business"); source.setStatus("active");
        source.setUsername("reader"); source.setPassword("read-fixture"); return source;
    }
}
