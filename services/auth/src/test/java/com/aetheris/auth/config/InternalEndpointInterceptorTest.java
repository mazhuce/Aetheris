package com.aetheris.auth.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalEndpointInterceptorTest {

    @Test
    @DisplayName("未配置内部令牌时 fail-closed：503 拒绝所有内部调用")
    void unconfiguredTokenFailsClosed() throws Exception {
        InternalEndpointInterceptor interceptor = new InternalEndpointInterceptor("");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(
                new MockHttpServletRequest("POST", "/internal/permissions/check"), response, null);

        assertFalse(pass);
        assertEquals(503, response.getStatus());
    }

    @Test
    @DisplayName("缺少 X-Internal-Token 头返回 401")
    void missingTokenRejected() throws Exception {
        InternalEndpointInterceptor interceptor = new InternalEndpointInterceptor("secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean pass = interceptor.preHandle(
                new MockHttpServletRequest("POST", "/internal/permissions/check"), response, null);

        assertFalse(pass);
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("令牌不匹配返回 401")
    void wrongTokenRejected() throws Exception {
        InternalEndpointInterceptor interceptor = new InternalEndpointInterceptor("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/permissions/check");
        request.addHeader(InternalEndpointInterceptor.TOKEN_HEADER, "attacker-guess");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    @DisplayName("令牌匹配放行")
    void matchingTokenPasses() throws Exception {
        InternalEndpointInterceptor interceptor = new InternalEndpointInterceptor("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/permissions/version");
        request.addHeader(InternalEndpointInterceptor.TOKEN_HEADER, "secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, null));
    }
}