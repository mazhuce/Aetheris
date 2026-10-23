package com.aetheris.metadata.service;

import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.mapper.DataSourceMapper;
import com.aetheris.metadata.service.impl.DataSourceServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class DataSourceCredentialPersistenceTest {
    DataSourceSecretService secrets=new DataSourceSecretService(Base64.getEncoder().encodeToString(new byte[32]));
    DataSourceMapper mapper=mock(DataSourceMapper.class);
    DataSourceServiceImpl service=new DataSourceServiceImpl(secrets);
    @BeforeEach void prepare(){ReflectionTestUtils.setField(service,"baseMapper",mapper);when(mapper.insert(any(DataSource.class))).thenReturn(1);when(mapper.update(any(DataSource.class),any())).thenReturn(1);}
    DataSource previous(){var ds=DataSourceWriteCredentialsTest.source();ds.setId(7L);ds.setPassword(secrets.encrypt("read-old"));ds.setWriteUsername("writer");ds.setWritePassword(secrets.encrypt("write-old"));when(mapper.selectById(7L)).thenReturn(ds);return ds;}
    @Test void saveEncryptsBothSecretsAndNeverCopiesReaderIntoWriter(){
        var fresh=DataSourceWriteCredentialsTest.source();fresh.setWriteUsername("writer");fresh.setWritePassword("write-new");assertTrue(service.save(fresh));
        assertTrue(secrets.isEncrypted(fresh.getPassword()));assertTrue(secrets.isEncrypted(fresh.getWritePassword()));assertEquals("write-new",secrets.decrypt(fresh.getWritePassword()));
        var readonly=DataSourceWriteCredentialsTest.source();assertTrue(service.save(readonly));assertFalse(readonly.isWriteConfigured());assertNull(readonly.getWritePassword());
    }
    @Test void blankPasswordPreservesOnlySameAccounts(){
        var old=previous();var changed=DataSourceWriteCredentialsTest.source();changed.setId(7L);changed.setPassword("");
        assertTrue(service.updateById(changed));assertEquals(old.getPassword(),changed.getPassword());assertEquals(old.getWriteUsername(),changed.getWriteUsername());assertEquals(old.getWritePassword(),changed.getWritePassword());
    }
    @Test void accountChangesCannotReuseMissingOrOldEncryptedPasswords(){
        var old=previous();
        for(String mode:List.of("readMissing","readCipher","writeMissing","writeCipher","sameWriter")){
            var patch=DataSourceWriteCredentialsTest.source();patch.setId(7L);patch.setPassword(null);
            switch(mode){case "readMissing"->patch.setUsername("new-reader");case "readCipher"->{patch.setUsername("new-reader");patch.setPassword(old.getPassword());}
                case "writeMissing"->patch.setWriteUsername("new-writer");case "writeCipher"->{patch.setWriteUsername("new-writer");patch.setWritePassword(old.getWritePassword());}
                case "sameWriter"->{patch.setWriteUsername("READER");patch.setWritePassword("new-fixture");}}
            assertThrows(IllegalArgumentException.class,()->service.updateById(patch),mode);
        }
        verify(mapper,never()).update(any(DataSource.class),any());
    }
    @Test void explicitDisableClearsBothColumnsRatherThanRelyingOnNullOmission(){
        previous();var patch=DataSourceWriteCredentialsTest.source();patch.setId(7L);patch.setPassword(null);patch.setDisableWriteCredentials(true);
        assertTrue(service.updateById(patch));assertNull(patch.getWriteUsername());assertNull(patch.getWritePassword());assertFalse(patch.isWriteConfigured());
        var conflict=DataSourceWriteCredentialsTest.source();conflict.setId(7L);conflict.setDisableWriteCredentials(true);conflict.setWriteUsername("writer");assertThrows(IllegalArgumentException.class,()->service.updateById(conflict));
    }
    @Test void staleInternalVersionAndUnversionedOldCipherAreRejected(){
        var old=previous();old.setConnectionRevision(2L);
        var stale=DataSourceWriteCredentialsTest.source();stale.setId(7L);stale.setConnectionRevision(1L);stale.setWriteUsername("writer");stale.setWritePassword(old.getWritePassword());
        assertFalse(service.updateById(stale));
        var opaque=new DataSource();opaque.setId(7L);opaque.setWritePassword(secrets.encrypt("old-password"));assertThrows(IllegalArgumentException.class,()->service.updateById(opaque));
        verify(mapper,never()).update(any(DataSource.class),any());
    }
    @Test void versionedLegacyEncryptionMigrationCanPersistBothNewCiphertexts(){
        var previous=previous();previous.setPassword("legacy-read");previous.setWritePassword("legacy-write");previous.setConnectionRevision(9L);
        var migrated=DataSourceWriteCredentialsTest.source();migrated.setId(7L);migrated.setConnectionRevision(9L);migrated.setPassword(secrets.encrypt("legacy-read"));migrated.setWriteUsername("writer");migrated.setWritePassword(secrets.encrypt("legacy-write"));
        assertTrue(service.updateById(migrated));assertEquals("legacy-read",secrets.decrypt(migrated.getPassword()));assertEquals("legacy-write",secrets.decrypt(migrated.getWritePassword()));
    }
}
