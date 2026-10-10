package com.aetheris.system.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SystemIdentityInterceptorTest {

    private static final String TOKEN = "internal-test-token";

    @Test
    void rejectsMissingSignature() throws Exception {
        SystemIdentityInterceptor interceptor = new SystemIdentityInterceptor(
                new IdentityVerifier(TOKEN, 300_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(!interceptor.preHandle(new MockHttpServletRequest("GET", "/api/system/activity"),
                response, mock(Object.class)));

        assertEquals(401, response.getStatus());
    }

    @Test
    void storesVerifiedIdentityForScopedRead() throws Exception {
        SystemIdentityInterceptor interceptor = new SystemIdentityInterceptor(
                new IdentityVerifier(TOKEN, 300_000L));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/activity");
        request.addHeader(IdentityVerifier.IDENTITY_HEADER,
                IdentityVerifierTestHelper.sign(42L, "alice", TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, mock(Object.class)));

        AetherisIdentity identity = (AetherisIdentity) request.getAttribute(
                SystemIdentityInterceptor.IDENTITY_ATTRIBUTE);
        assertEquals("alice", identity.username());
        assertEquals(42L, identity.uid());
    }
}
