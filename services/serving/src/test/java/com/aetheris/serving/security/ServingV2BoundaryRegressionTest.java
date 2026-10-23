package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class ServingV2BoundaryRegressionTest {
    private static final String TOKEN="serving-retired-hmac-fixture-at-least-32-bytes";
    @Test void ordinaryRequestAttributeCannotReplaceVerifiedUser() {
        var security=new ServingSecurity(new IdentityVerifier(TOKEN,300000),RestClient.builder().build());
        var request=new MockHttpServletRequest();
        request.setAttribute(ServingSecurity.ATTRIBUTE,new ServingIdentity(1L,"admin"));
        assertThatThrownBy(()->security.requireIdentity(request)).isInstanceOf(ServingSecurity.UnauthorizedException.class);
    }
    @Test void retiredSharedHmacIsNotAUserCredential() {
        var security=new ServingSecurity(new IdentityVerifier(TOKEN,300000),RestClient.builder().build());
        var request=new MockHttpServletRequest();
        request.addHeader("X-Aetheris-Identity",new ServiceIdentitySigner(TOKEN).sign("admin"));
        assertThatThrownBy(()->security.requireIdentity(request)).isInstanceOf(ServingSecurity.UnauthorizedException.class);
    }
}
