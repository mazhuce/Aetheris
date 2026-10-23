package com.aetheris.ingestion.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IngestionV2BoundaryRegressionTest {
    @Test void sharedHmacIsNoLongerAUserProof() throws Exception {
        String secret="ingestion-local-regression-secret";
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(("42|alice|"+System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        var request=new MockHttpServletRequest("GET","/api/ingestion/tasks");
        request.addHeader("X-Aetheris-Identity",raw+"."+HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));
        var response=new MockHttpServletResponse();
        assertFalse(new GatewayIdentityInterceptor(secret,300000,user->true).preHandle(request,response,new Object()));
        assertEquals(401,response.getStatus());
    }
    @Test void ordinaryUsernameAttributeDoesNotEstablishAUser() {
        var request=new MockHttpServletRequest();request.setAttribute(GatewayIdentityInterceptor.USERNAME_ATTRIBUTE,"admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try{assertNull(new ResourceAuthorization().username());}finally{RequestContextHolder.resetRequestAttributes();}
    }
}
