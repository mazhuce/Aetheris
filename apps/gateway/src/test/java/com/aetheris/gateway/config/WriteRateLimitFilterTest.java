package com.aetheris.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriteRateLimitFilterTest {

    @Test
    void limitsWritesButAllowsReads() {
        WriteRateLimitFilter filter = new WriteRateLimitFilter(1, 60);

        MockServerWebExchange first = exchange("POST", "/api/metadata/datasources");
        filter.filter(first, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete();
        }).block();
        assertEquals(HttpStatus.OK, first.getResponse().getStatusCode());

        MockServerWebExchange second = exchange("POST", "/api/metadata/datasources");
        filter.filter(second, ignored -> ignored.getResponse().setComplete()).block();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, second.getResponse().getStatusCode());

        MockServerWebExchange read = exchange("GET", "/api/metadata/datasources");
        filter.filter(read, current -> {
            current.getResponse().setStatusCode(HttpStatus.OK);
            return current.getResponse().setComplete();
        }).block();
        assertEquals(HttpStatus.OK, read.getResponse().getStatusCode());
    }

    private MockServerWebExchange exchange(String method, String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(
                org.springframework.http.HttpMethod.valueOf(method), path).build());
    }
}
