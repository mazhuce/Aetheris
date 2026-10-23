package com.aetheris.system.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminEndpointInterceptorTest {

    private static final String TOKEN = "internal-test-token";

    private static MockHttpServletRequest signedRequest(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/audit-logs");
        if (header != null) {
            request.addHeader(IdentityVerifier.IDENTITY_HEADER, header);
        }
        return request;
    }

    @Test
    void legacyConfigurationDoesNotAuthenticateWithoutFilter() throws Exception {
        AuthPermissionClient client = mock(AuthPermissionClient.class);
        AdminEndpointInterceptor interceptor = new AdminEndpointInterceptor(
                new IdentityVerifier("", 300_000L), client);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(!interceptor.preHandle(
                signedRequest(IdentityVerifierTestHelper.sign(42L,"alice",TOKEN)),
                response, mock(Object.class)));

        assertEquals(401, response.getStatus());
        verify(client, never()).mayManage(anyString());
    }

    @Test
    void missingSignatureIsRejected() throws Exception {
        AuthPermissionClient client = mock(AuthPermissionClient.class);
        AdminEndpointInterceptor interceptor = new AdminEndpointInterceptor(
                new IdentityVerifier(TOKEN, 300_000L), client);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(signedRequest(null), response, mock(Object.class));

        assertEquals(401, response.getStatus());
        verify(client, never()).mayManage(anyString());
    }

    @Test
    void signedIdentityWithoutSystemManagementPermissionIsForbidden() throws Exception {
        AuthPermissionClient client = mock(AuthPermissionClient.class);
        when(client.mayManage("alice")).thenReturn(false);
        AdminEndpointInterceptor interceptor = new AdminEndpointInterceptor(
                new IdentityVerifier(TOKEN, 300_000L), client);
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(
                V2TestFixture.trusted(42,"alice").postProcessRequest(signedRequest(null)),
                response, mock(Object.class));

        assertEquals(403, response.getStatus());
    }

    @Test
    void signedAdministratorPasses() throws Exception {
        AuthPermissionClient client = mock(AuthPermissionClient.class);
        when(client.mayManage("admin")).thenReturn(true);
        AdminEndpointInterceptor interceptor = new AdminEndpointInterceptor(
                new IdentityVerifier(TOKEN, 300_000L), client);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(
                V2TestFixture.trusted(1,"admin").postProcessRequest(signedRequest(null)),
                response, mock(Object.class)));
    }

    @Test
    void forgedUsernameHeaderCannotReplaceSignedIdentity() throws Exception {
        // 直接请求时伪造 X-User-Name=admin，但签名里的身份仍是 alice →
        // 按 alice 判定管理权限。
        AuthPermissionClient client = mock(AuthPermissionClient.class);
        when(client.mayManage("alice")).thenReturn(false);
        AdminEndpointInterceptor interceptor = new AdminEndpointInterceptor(
                new IdentityVerifier(TOKEN, 300_000L), client);
        MockHttpServletRequest request = V2TestFixture.trusted(42,"alice").postProcessRequest(signedRequest(null));
        request.addHeader("X-User-Name", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, mock(Object.class));

        assertEquals(403, response.getStatus());
        verify(client).mayManage("alice");
    }
}