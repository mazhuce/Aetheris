package com.aetheris.metadata.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class DataSourceSecretServiceTest {

    private final DataSourceSecretService service = new DataSourceSecretService(
            Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsWithRandomNonceAndDecrypts() {
        String first = service.encrypt("p@ssword");
        String second = service.encrypt("p@ssword");

        assertTrue(service.isEncrypted(first));
        assertNotEquals("p@ssword", first);
        assertNotEquals(first, second);
        assertEquals("p@ssword", service.decrypt(first));
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = service.encrypt("secret");
        int tamperAt = encrypted.length() - 5;
        char original = encrypted.charAt(tamperAt);
        char replacement = original == 'A' ? 'B' : 'A';
        String tampered = encrypted.substring(0, tamperAt) + replacement
                + encrypted.substring(tamperAt + 1);

        assertThrows(IllegalStateException.class, () -> service.decrypt(tampered));
    }

    @Test
    void leavesLegacyPlaintextReadableForMigration() {
        assertEquals("legacy", service.decrypt("legacy"));
        assertFalse(service.isEncrypted("legacy"));
    }

    @Test
    void requiresAes256MasterKey() {
        assertThrows(IllegalStateException.class, () -> new DataSourceSecretService("short"));
    }
}
