package com.aetheris.metadata.fixedexecution;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FixedTransferExecutionTest {
    DataSource ds(long id){var d=new DataSource();d.setId(id);d.setType("postgresql");d.setStatus("active");d.setUsername("reader");d.setPassword("fixture-only");d.setWriteUsername("writer");d.setWritePassword("fixture-only");d.setConnectionRevision(0L);return d;}
    @Test void initialZeroRevisionIsValidAndNegativeOrMissingRevisionIsNot()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var sources=mock(DataSourceService.class);var jdbc=mock(JdbcService.class);var execution=new FixedTransferExecution(sources,jdbc);
            var source=ds(5);var target=ds(6);when(sources.getById(5L)).thenReturn(source);when(sources.getById(6L)).thenReturn(target);
            var manifest=f.install(11,5,6,"SELECT 1","business.output",s->s);var p=execution.prepare(manifest);
            assertEquals(0,FixedJson.number(FixedJson.parse(p.normalized()),"sourceConnectionRevision"));
            source.setConnectionRevision(-1L);assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(manifest));
            source.setConnectionRevision(null);assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(manifest));verifyNoInteractions(jdbc);
        }
    }
    @Test void managedDisabledMissingWriterAndChangedConnectionAreRejectedBeforeJdbc()throws Exception{
        try(var f=new FixedTransferTestPeers()){
            var sources=mock(DataSourceService.class);var jdbc=mock(JdbcService.class);var execution=new FixedTransferExecution(sources,jdbc);
            var source=ds(5);var target=ds(6);when(sources.getById(5L)).thenReturn(source);when(sources.getById(6L)).thenReturn(target);var m=f.install(11,5,6,"SELECT 1","business.output",s->s);
            String normalized=execution.prepare(m).normalized();source.setManaged(true);assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));source.setManaged(false);
            source.setStatus("DISABLED");assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));source.setStatus("active");
            target.setWriteUsername("READER");assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));target.setWriteUsername("writer");
            target.setWritePassword(null);assertThrows(FixedTransferPeers.Rejected.class,()->execution.prepare(m));target.setWritePassword("fixture-only");
            target.setConnectionRevision(1L);assertThrows(FixedTransferPeers.Rejected.class,()->execution.verify(m,normalized));verifyNoInteractions(jdbc);
        }
    }
}
