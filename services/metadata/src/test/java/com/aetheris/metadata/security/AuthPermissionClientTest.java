package com.aetheris.metadata.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthPermissionClientTest {

    @Test
    void allowsCollectionPermissionChecksWithNullResourceId() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://auth-service")
                .defaultHeader("X-Internal-Token", "test-token");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AuthPermissionClient client = new AuthPermissionClient(builder.build());

        server.expect(requestTo("http://auth-service/internal/permissions/check"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "test-token"))
                .andExpect(content().json("""
                        {
                          "username": "liuyafei",
                          "resourceType": "datasource",
                          "resourceId": null,
                          "action": "view",
                          "ctx": {}
                        }
                        """))
                .andRespond(withSuccess("{\"allowed\":true}", MediaType.APPLICATION_JSON));

        AuthPermissionClient.Access access = client.check(
                "liuyafei", "datasource", null, "view", Map.of());

        assertTrue(access.allowed());
        server.verify();
    }

    @Test
    void authFailureIsMarkedUnavailableAndFailClosed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://auth-service");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AuthPermissionClient client = new AuthPermissionClient(builder.build(), meterRegistry);

        server.expect(requestTo("http://auth-service/internal/permissions/check"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        AuthPermissionClient.Access access = client.check("alice", "metric", 1L, "view", Map.of());

        assertFalse(access.allowed());
        assertFalse(access.available());
        assertEquals(1.0, meterRegistry.counter("aetheris.auth.permission.check.unavailable").count());
        assertEquals(1.0, meterRegistry.counter("aetheris.auth.permission.check.requests").count());
        server.verify();
    }
}
