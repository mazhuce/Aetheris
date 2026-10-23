package com.aetheris.metadata.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MetadataV2BoundaryRegressionTest {
    static final String OLD="metadata-regression-old-shared-secret-with-entropy";
    @Test void sharedHmacCannotEstablishMetadataUser() throws Exception {
        var security=new MetadataSecurity(mock(AuthPermissionClient.class));
        var request=new MockHttpServletRequest("GET","/api/metadata/datasources");
        String value=Base64.getUrlEncoder().withoutPadding().encodeToString(("1|admin|"+System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(OLD.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        request.addHeader("X-Aetheris-Identity",value+"."+java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))));
        request.addHeader("X-User-Name","admin");assertNull(security.identity(request));
    }
    @Test void viewDtoAttributeIsNotAnAuthenticatedProof() {
        var security=new MetadataSecurity(mock(AuthPermissionClient.class));
        var request=new MockHttpServletRequest("GET","/api/metadata/datasources");
        request.setAttribute(MetadataSecurity.IDENTITY_ATTRIBUTE,new AetherisIdentity(1L,"admin"));
        assertNull(security.identity(request));
    }
}
