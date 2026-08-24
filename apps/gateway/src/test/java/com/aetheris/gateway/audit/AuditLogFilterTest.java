package com.aetheris.gateway.audit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogFilterTest {

    @Test
    void recordsAuthenticatedMutation() {
        AuditLogClient client = mock(AuditLogClient.class);
        when(client.record(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/quality/rules")
                        .header("X-User-Name", "admin")
                        .header("X-User-Role", "admin")
                        .build());

        new AuditLogFilter(client).filter(exchange, filteredExchange -> {
            filteredExchange.getResponse().setStatusCode(HttpStatus.CREATED);
            return filteredExchange.getResponse().setComplete();
        }).block();

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(client).record(captor.capture());
        assertEquals("admin", captor.getValue().username());
        assertEquals("POST", captor.getValue().method());
        assertEquals(201, captor.getValue().statusCode());
    }

    @Test
    void ignoresReadRequest() {
        AuditLogClient client = mock(AuditLogClient.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/quality/rules").build());

        new AuditLogFilter(client).filter(
                exchange,
                filteredExchange -> filteredExchange.getResponse().setComplete()).block();

        verify(client, never()).record(any());
    }
}
