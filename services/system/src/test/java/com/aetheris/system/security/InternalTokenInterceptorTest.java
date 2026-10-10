package com.aetheris.system.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class InternalTokenInterceptorTest {

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("shared-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/notifications");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(!interceptor.preHandle(request, response, mock(Object.class)));
        assertEquals(401, response.getStatus());
    }

    @Test
    void acceptsSharedToken() throws Exception {
        InternalTokenInterceptor interceptor = new InternalTokenInterceptor("shared-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/notifications");
        request.addHeader("X-Internal-Token", "shared-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, mock(Object.class)));
    }
}
