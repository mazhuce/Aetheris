package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceIdentitySignerTest {

    private static final String TOKEN = "01234567890123456789012345678901";

    @Test
    void signsApiKeyOwnerUsingGatewayCompatibleProtocol() {
        ServiceIdentitySigner signer = new ServiceIdentitySigner(TOKEN);
        String value = signer.sign("partner-a", 1_700_000_000_000L);
        int dot = value.lastIndexOf('.');
        assertEquals("-|partner-a|1700000000000",
                new String(Base64.getUrlDecoder().decode(value.substring(0, dot)), StandardCharsets.UTF_8));
        assertEquals(64, value.substring(dot + 1).length());
    }

    @Test
    void failsClosedForInvalidOwner() {
        ServiceIdentitySigner signer = new ServiceIdentitySigner(TOKEN);
        assertTrue(signer.sign("").isEmpty());
        assertTrue(signer.sign("partner|a").isEmpty());
        assertTrue(new ServiceIdentitySigner("").sign("partner-a").isEmpty());
    }
}
