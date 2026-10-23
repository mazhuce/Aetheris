package com.aetheris.serving.security;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class ServingPermissionFilterTest {
    @Test void listUsesExactResourceIdsAndAuthFailureIsUnavailable() throws Exception {
        try(var fixture=new V2ServingFixture()){
            fixture.request(request->{
                assertEquals(List.of(8L),fixture.authorization.filter(request,"dashboard",List.of(8L,9L),id->id));
                fixture.outsideBatch=true;
                var failure=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fixture.authorization.filter(request,"dashboard",List.of(8L),id->id));
                assertEquals(503,failure.getStatusCode().value());
                fixture.outsideBatch=false;fixture.fault="unsigned";
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->fixture.authorization.filter(request,"dashboard",List.of(8L),id->id));
            });
        }
    }
}
