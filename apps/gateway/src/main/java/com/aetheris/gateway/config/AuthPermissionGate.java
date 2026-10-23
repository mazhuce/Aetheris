package com.aetheris.gateway.config;

import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Authenticated v2 responses only; permission failures remain fail-closed. */
@Component
public class AuthPermissionGate implements PermissionGate {
    private final AuthProtocolClient client;
    public AuthPermissionGate(AuthProtocolClient client) { this.client = client; }
    public Mono<Boolean> mayMutate(String userProof, String service) {
        return Mono.defer(() -> Mono.fromFuture(client.mayMutate(userProof, service))).map(AuthProtocolClient.Decision::allowed);
    }
    public Mono<Boolean> mayManage(String userProof) {
        return Mono.defer(() -> Mono.fromFuture(client.mayManage(userProof))).map(AuthProtocolClient.Decision::allowed);
    }
    public Mono<Boolean> sessionActive(Long uid, String username, long accessVersion) {
        return Mono.defer(() -> Mono.fromFuture(client.sessionActive(uid, username, accessVersion)));
    }
}
