package com.aetheris.quality.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class InternalTokenInterceptorTest {
    @Test
    void acceptsOnlyConstantTimeMatchingToken() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("x".repeat(32));
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getHeader("X-Internal-Token")).thenReturn("x".repeat(32));
        assertTrue(interceptor.preHandle(request, response, new Object()));

        when(request.getHeader("X-Internal-Token")).thenReturn("wrong");
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(new java.io.StringWriter()));
        assertFalse(interceptor.preHandle(request, response, new Object()));
        verify(response).setStatus(401);
    }
}
