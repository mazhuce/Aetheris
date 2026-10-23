package com.aetheris.metadata.fixedexecution;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FixedSqlExecutionTest {
    static DataSource source(){var d=new DataSource();d.setId(8L);d.setUsername("reader");d.setPassword("read-secret-fixture");d.setWriteUsername("writer");d.setWritePassword("write-secret-fixture");d.setConnectionRevision(0L);d.setType("postgresql");d.setStatus("active");d.setManaged(false);return d;}
    @Test void missingSameAccountManagedAndChangedRevisionNeverReachJdbc() throws Exception {
        try(var p=new FixedSqlTestPeers()){
            var m=p.install(11,8,"INSERT INTO business.sql_output SELECT 1",30,s->s);var sources=mock(DataSourceService.class);var jdbc=mock(JdbcService.class);var d=source();when(sources.getById(8L)).thenReturn(d);var execution=new FixedSqlExecution(sources,jdbc);var normalized=execution.prepare(m);assertFalse(normalized.contains("secret"));assertFalse(normalized.contains("reader"));
            d.setWriteUsername("READER");assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));d.setWriteUsername("writer");d.setWritePassword(null);assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));d.setWritePassword("write-secret-fixture");
            d.setManaged(true);assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));d.setManaged(false);d.setStatus("DISABLED");assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));d.setStatus("active");
            d.setConnectionRevision(1L);assertThrows(FixedTransferPeers.Rejected.class,()->execution.verify(m,normalized));assertThrows(FixedTransferPeers.Rejected.class,()->execution.execute(m,normalized,new FixedExecutionAdapter.DispatchBudget(Instant.now().plusSeconds(20))));verifyNoInteractions(jdbc);
        }
    }
    @Test void onlyDeadlineAwareWriteHookReceivesFreshAccountAndNullableCount() throws Exception {
        try(var p=new FixedSqlTestPeers()){
            var m=p.install(11,8,"INSERT INTO business.sql_output SELECT 1",30,s->s);var sources=mock(DataSourceService.class);var jdbc=mock(JdbcService.class);var d=source();when(sources.getById(8L)).thenReturn(d);var execution=new FixedSqlExecution(sources,jdbc);var normalized=execution.prepare(m);var deadline=Instant.now().plusSeconds(20);
            when(jdbc.execute(eq(d),eq(m.definition().sql()),eq(30),eq(deadline))).thenReturn(Map.of("success",true,"affectedRows",-1,"durationMs",2));
            assertNull(execution.execute(m,normalized,new FixedExecutionAdapter.DispatchBudget(deadline)).affectedRows());verify(jdbc,never()).execute(any(),any(),any());verify(jdbc,never()).query(any(),any());
        }
    }
}
