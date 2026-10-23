package com.aetheris.metadata.config;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceSecretMigrationTest {
    @Test void encryptsBothLegacySecretsWithoutDroppingTheInternalSnapshotFence(){
        var source=new DataSource();source.setId(7L);source.setUsername("reader");source.setPassword("legacy-read");
        source.setWriteUsername("writer");source.setWritePassword("legacy-write");source.setConnectionRevision(9L);
        var service=mock(DataSourceService.class);when(service.list()).thenReturn(List.of(source));
        var secrets=new DataSourceSecretService(Base64.getEncoder().encodeToString(new byte[32]));
        new DataSourceSecretMigration(service,secrets).run();verify(service).updateById(source);
        assertEquals("legacy-read",secrets.decrypt(source.getPassword()));assertTrue(secrets.isEncrypted(source.getPassword()));
        assertEquals("legacy-write",secrets.decrypt(source.getWritePassword()));assertTrue(secrets.isEncrypted(source.getWritePassword()));
        assertEquals(9L,source.getConnectionRevision());
    }
}
