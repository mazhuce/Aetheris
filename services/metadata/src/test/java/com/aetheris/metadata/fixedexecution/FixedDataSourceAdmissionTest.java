package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.DataSourceService;
import com.aetheris.metadata.service.JdbcService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedDataSourceAdmissionTest {
    static FixedSqlTestPeers sqlPeers;
    static FixedQualityTestPeers qualityPeers;
    static FixedTransferTestPeers transferPeers;
    @BeforeAll static void peers() throws Exception {
        sqlPeers = new FixedSqlTestPeers(); qualityPeers = new FixedQualityTestPeers(); transferPeers = new FixedTransferTestPeers();
    }
    @AfterAll static void closePeers() throws Exception {
        if (sqlPeers != null) sqlPeers.close(); if (qualityPeers != null) qualityPeers.close(); if (transferPeers != null) transferPeers.close();
    }
    static Stream<String> nonActive() {
        return Arrays.asList(null, "", "inactive", "disabled", "isolated", "unknown", "ACTIVE", "active ").stream();
    }
    static DataSource source(long id) {
        var source = new DataSource(); source.setId(id); source.setType("postgresql");
        source.setUsername("reader"); source.setPassword("fixture-reader");
        source.setWriteUsername("writer"); source.setWritePassword("fixture-writer");
        source.setConnectionRevision(0L); source.setStatus("active"); return source;
    }

    @ParameterizedTest @MethodSource("nonActive")
    void submissionRequiresActiveSourceAndTarget(String status) {
        var sources = mock(DataSourceService.class); var jdbc = mock(JdbcService.class);
        var reader = source(8); var writer = source(9); reader.setStatus(status); writer.setStatus(status);
        when(sources.getById(8L)).thenReturn(reader); when(sources.getById(9L)).thenReturn(writer);
        var sql = sqlPeers.install(11, 8, "INSERT INTO business.sql_output SELECT 1", 30, text -> text);
        var quality = qualityPeers.install(11, 8, "sql", "SELECT 0", 0, text -> text);
        var transfer = transferPeers.install(11, 8, 9, "SELECT 1", "business.output", text -> text);
        var transferExecution = new FixedTransferExecution(sources, jdbc);
        assertAll(
                () -> assertThrows(FixedTransferPeers.Rejected.class, () -> new FixedSqlExecution(sources, jdbc).prepare(sql)),
                () -> assertThrows(FixedTransferPeers.Rejected.class, () -> new FixedQualityExecution(sources, jdbc).prepare(quality)),
                () -> assertThrows(FixedTransferPeers.Rejected.class, () -> transferExecution.prepare(transfer)));
        reader.setStatus("active");
        assertThrows(FixedTransferPeers.Rejected.class, () -> transferExecution.prepare(transfer));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest @MethodSource("nonActive")
    void sourceDisabledAfterPreparationNeverReachesJdbc(String status) {
        var sources = mock(DataSourceService.class); var jdbc = mock(JdbcService.class);
        var reader = source(8); var writer = source(9);
        when(sources.getById(8L)).thenReturn(reader); when(sources.getById(9L)).thenReturn(writer);
        var sql = sqlPeers.install(12, 8, "INSERT INTO business.sql_output SELECT 1", 30, text -> text);
        var quality = qualityPeers.install(12, 8, "sql", "SELECT 0", 0, text -> text);
        var transfer = transferPeers.install(12, 8, 9, "SELECT 1", "business.output", text -> text);
        var sqlExecution = new FixedSqlExecution(sources, jdbc);
        var qualityExecution = new FixedQualityExecution(sources, jdbc);
        var transferExecution = new FixedTransferExecution(sources, jdbc);
        String sqlStored = sqlExecution.prepare(sql), qualityStored = qualityExecution.prepare(quality);
        var transferStored = transferExecution.prepare(transfer);
        // A separately loaded new row leaves the admitted snapshot unchanged.
        var changed = source(8); changed.setStatus(status); when(sources.getById(8L)).thenReturn(changed);
        assertAll(
                () -> assertThrows(FixedTransferPeers.Rejected.class, () -> sqlExecution.execute(sql, sqlStored,
                        new FixedExecutionAdapter.DispatchBudget(Instant.now().plusSeconds(20)))),
                () -> assertThrows(FixedTransferPeers.Rejected.class, () -> qualityExecution.execute(quality, qualityStored)),
                () -> assertThrows(FixedTransferPeers.Rejected.class, () -> transferExecution.execute(transferStored)));
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest @MethodSource("nonActive")
    void targetDisabledAfterPreparationNeverReachesTransferJdbc(String status) {
        var sources = mock(DataSourceService.class); var jdbc = mock(JdbcService.class);
        when(sources.getById(8L)).thenReturn(source(8)); when(sources.getById(9L)).thenReturn(source(9));
        var transfer = transferPeers.install(13, 8, 9, "SELECT 1", "business.output", text -> text);
        var execution = new FixedTransferExecution(sources, jdbc); var admitted = execution.prepare(transfer);
        var changed = source(9); changed.setStatus(status); when(sources.getById(9L)).thenReturn(changed);
        assertThrows(FixedTransferPeers.Rejected.class, () -> execution.execute(admitted));
        verifyNoInteractions(jdbc);
    }
}
