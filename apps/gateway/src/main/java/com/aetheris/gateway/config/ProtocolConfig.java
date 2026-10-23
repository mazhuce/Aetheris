package com.aetheris.gateway.config;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.configuration.ProtocolInstallation;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Qualifier;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;

@Configuration
public class ProtocolConfig {
    @Bean ProtocolInstallation protocolInstallation(Environment env) {
        return ProtocolInstallation.load("gateway", Path.of(required(env, "registry-file")), required(env, "workload-kid"),
                Path.of(required(env, "workload-private-file")), required(env, "user-kid"),
                Path.of(required(env, "user-private-file")), Clock.systemUTC());
    }
    @Bean(destroyMethod = "close") SignedHttpClient gatewayAuthTransport(ProtocolInstallation installation, Environment env) {
        return new SignedHttpClient("gateway", installation.workloadSigner(), installation.verifier(), Clock.systemUTC(),
                TimePolicy.defaults(), Map.of("auth", URI.create(env.getRequiredProperty("aetheris.auth.base-url"))),
                Map.of("auth", java.util.Set.of("auth.session.check", "auth.permission.manage", "auth.permission.mutate")),
                new SignedHttpClient.Limits(1024 * 1024, 1024 * 1024, 32, Duration.ofMillis(500), Duration.ofSeconds(2)));
    }
    @Bean AuthProtocolClient authProtocolClient(@Qualifier("gatewayAuthTransport") SignedHttpClient gatewayAuthTransport) { return new AuthProtocolClient(gatewayAuthTransport); }
    @Bean(destroyMethod = "close") SignedHttpClient gatewaySystemTransport(ProtocolInstallation installation, Environment env) {
        return new SignedHttpClient("gateway", installation.workloadSigner(), installation.verifier(), Clock.systemUTC(),
                TimePolicy.defaults(), Map.of("system", URI.create(env.getRequiredProperty("audit.base-url"))),
                Map.of("system", java.util.Set.of("system.audit.record")),
                new SignedHttpClient.Limits(16 * 1024, 1024, 16, Duration.ofMillis(500), Duration.ofSeconds(2)));
    }
    private static String required(Environment env, String name) { return env.getRequiredProperty("aetheris.security." + name); }
}
