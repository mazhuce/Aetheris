package com.aetheris.workflow.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionOutboundTransportTest {
    @Test
    void actualFlinkTransportRejectsRedirectWithoutFollowingIt() throws Exception {
        var trap = new AtomicInteger();
        HttpServer server = redirectServer(trap);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new FlinkSqlExecutionClient(RestClient.builder(), Duration.ofSeconds(1), 100, new OutboundTargetPolicy());
            assertThrows(FlinkSqlExecutionClient.FlinkExecutionException.class, () -> client.health(base));
            assertEquals(0, trap.get());
        } finally { server.stop(0); }
    }

    @Test
    void actualRunnerTransportRejectsRedirectAndUntrustedOrigin() throws Exception {
        var trap = new AtomicInteger();
        HttpServer server = redirectServer(trap);
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new SparkBatchExecutionAdapter(RestClient.builder(), base,
                    "audit-only-runner-token-with-more-than-32-bytes", true, Duration.ofSeconds(1), new OutboundTargetPolicy(), "");
            assertThrows(SparkRunnerException.class, client::health);
            assertEquals(0, trap.get());
            assertThrows(IllegalArgumentException.class, () -> client.health("http://127.0.0.1:12345"));
        } finally { server.stop(0); }
    }

    @Test
    void actualFlinkTransportStillReadsLegitimateLocalGateway() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/info", exchange -> {
            byte[] body = "{\"version\":\"audit-local\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var client = new FlinkSqlExecutionClient(RestClient.builder(), Duration.ofSeconds(1), 100, new OutboundTargetPolicy());
            assertEquals("audit-local", client.health("http://127.0.0.1:" + server.getAddress().getPort()).version());
        } finally { server.stop(0); }
    }

    private HttpServer redirectServer(AtomicInteger trap) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/trap")) {
                trap.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/trap");
                exchange.sendResponseHeaders(302, -1);
            }
            exchange.close();
        });
        server.start();
        return server;
    }
}
