package com.aetheris.system.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class SystemV2BoundaryRegressionTest {
    @Test void oldHmacCannotBecomeSystemIdentity() throws Exception {
        var request=new MockHttpServletRequest("GET","/api/system/activity");
        request.addHeader("X-Aetheris-Identity",IdentityVerifierTestHelper.sign(1L,"admin","old-shared-token"));
        var response=new MockHttpServletResponse();
        assertFalse(new SystemIdentityInterceptor(new IdentityVerifier("old-shared-token",300_000)).preHandle(request,response,new Object()));
        assertEquals(401,response.getStatus());
    }
    @Test void oldSharedTokenCannotAuthorizeInternalRoute() throws Exception {
        var request=new MockHttpServletRequest("POST","/internal/notifications");request.addHeader("X-Internal-Token","old-shared-token");
        assertFalse(new InternalTokenInterceptor("old-shared-token").preHandle(request,new MockHttpServletResponse(),new Object()));
    }
}
