package com.aetheris.gateway.audit;

import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class AuditLogClient {
    private static final Logger log = LoggerFactory.getLogger(AuditLogClient.class);
    private final SignedHttpClient transport;
    private final ObjectMapper json;
    public AuditLogClient(@Qualifier("gatewaySystemTransport") SignedHttpClient transport, ObjectMapper json) {
        this.transport = transport; this.json = json;
    }
    public Mono<Void> record(AuditEvent event) {
        return Mono.defer(() -> {
            try {
                byte[] bytes = json.writeValueAsBytes(event);
                return Mono.fromFuture(transport.sendAsync(new SignedHttpClient.Request("system", "POST",
                        "/internal/v2/audit/logs", "system.audit.record", bytes))).flatMap(response ->
                        response.status() == 202 && response.body().length == 0 ? Mono.<Void>empty()
                                : Mono.error(new IllegalStateException("Audit v2 response rejected")));
            } catch (Exception error) { return Mono.<Void>error(error); }
        }).doOnError(error -> log.warn("Audit log delivery failed ({})", error.getClass().getSimpleName()))
                .onErrorResume(error -> Mono.empty());
    }
}
