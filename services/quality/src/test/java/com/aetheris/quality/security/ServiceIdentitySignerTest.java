package com.aetheris.quality.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceIdentitySignerTest {

    private static final String TOKEN = "01234567890123456789012345678901";

    @Test
    void signsGatewayCompatibleServiceIdentity() {
        ServiceIdentitySigner signer = new ServiceIdentitySigner(TOKEN, "quality-reader");
        String value = signer.sign("quality-reader", 1_700_000_000_000L);
        int dot = value.lastIndexOf('.');
        String payload = new String(Base64.getUrlDecoder().decode(value.substring(0, dot)), StandardCharsets.UTF_8);
        assertEquals("-|quality-reader|1700000000000", payload);
        assertEquals(64, value.substring(dot + 1).length());
    }

    @Test
    void failsClosedWhenCredentialsAreMissing() {
        assertTrue(new ServiceIdentitySigner("", "quality-reader").sign().isEmpty());
        assertTrue(new ServiceIdentitySigner(TOKEN, "").sign().isEmpty());
    }
}
