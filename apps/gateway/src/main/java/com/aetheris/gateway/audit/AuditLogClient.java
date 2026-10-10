package com.aetheris.gateway.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class AuditLogClient {

    private static final Logger log = LoggerFactory.getLogger(AuditLogClient.class);

    private final WebClient webClient;

    public AuditLogClient(
            WebClient.Builder builder,
            @Value("${audit.base-url}") String baseUrl,
            @Value("${aetheris.internal-token:}") String internalToken) {
        this.webClient = builder.baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken == null ? "" : internalToken)
                .build();
    }

    public Mono<Void> record(AuditEvent event) {
        return webClient.post()
                .uri("/internal/audit/logs")
                .bodyValue(event)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(2))
                .doOnError(error -> log.warn("Audit log delivery failed: {}", error.getMessage()))
                .onErrorResume(error -> Mono.empty())
                .then();
    }
}
