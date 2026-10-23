package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServingApiKeyRouteTest {
    @Test
    void onlyExactPublishedPostDelegatesKeyAuthenticationToTheController() throws Exception {
        IdentityVerifier verifier = mock(IdentityVerifier.class);
        ServingIdentityInterceptor interceptor = new ServingIdentityInterceptor(verifier);
        MockHttpServletRequest valid = new MockHttpServletRequest("POST", "/api/serving/v1/apis/ORDERS/query");
        valid.addHeader("X-API-Key", "presented");
        assertTrue(interceptor.preHandle(valid, new MockHttpServletResponse(), new Object()));
        for (String path : java.util.List.of("/api/serving/v1/apis", "/api/serving/v1/apis/ORDERS/query/",
                "/api/serving/v1/apis/ORDERS/publish")) {
            MockHttpServletRequest invalid = new MockHttpServletRequest("POST", path);
            invalid.addHeader("X-API-Key", "presented");
            assertFalse(interceptor.preHandle(invalid, new MockHttpServletResponse(), new Object()));
        }
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/serving/v1/apis/ORDERS/query");
        get.addHeader("X-API-Key", "presented");
        assertFalse(interceptor.preHandle(get, new MockHttpServletResponse(), new Object()));
    }
}
