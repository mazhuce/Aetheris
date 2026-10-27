package com.aetheris.gateway.config;

import com.aetheris.security.protocol.*;
import com.aetheris.security.protocol.authorization.AuthProtocolClient;
import com.aetheris.security.protocol.transport.SignedHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class AuthPermissionGateTest {
    HttpServer server; ExecutorService handlers; SignedHttpClient transport; AuthPermissionGate gate;
    final AtomicReference<String> answer = new AtomicReference<>("{\"active\":true}");
    final AtomicReference<String> observedPath = new AtomicReference<>(), observedUser = new AtomicReference<>();
    final AtomicReference<com.fasterxml.jackson.databind.JsonNode> observedBody = new AtomicReference<>();
    final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    final AtomicBoolean unsigned = new AtomicBoolean(); final AtomicInteger calls = new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        Clock clock = Clock.systemUTC(); var gen = KeyPairGenerator.getInstance("Ed25519"); var g = gen.generateKeyPair(); var a = gen.generateKeyPair();
        var keys = new KeyRegistry(List.of(new KeyRegistry.Entry("gateway-workload", KeyPurpose.WORKLOAD, "gateway", g.getPublic()),
                new KeyRegistry.Entry("auth-workload", KeyPurpose.WORKLOAD, "auth", a.getPublic())));
        var signer = new ProtocolSigner("gateway-workload", KeyPurpose.WORKLOAD, "gateway", g.getPrivate(), keys, clock, TimePolicy.defaults());
        var auth = new ProtocolSigner("auth-workload", KeyPurpose.WORKLOAD, "auth", a.getPrivate(), keys, clock, TimePolicy.defaults());
        var verifier = new ProtocolVerifier(keys, clock, TimePolicy.defaults());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); handlers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try {
                calls.incrementAndGet(); var path = exchange.getRequestURI().getRawPath(); observedPath.set(path);
                String op = path.equals("/internal/v2/browser-sessions/check") ? AuthProtocolClient.BROWSER_SESSION_OPERATION
                        : path.endsWith("sessions/check") ? "auth.session.check" : path.endsWith("may-manage") ? "auth.permission.manage" : "auth.permission.mutate";
                byte[] body = exchange.getRequestBody().readAllBytes(); observedBody.set(new ObjectMapper().readTree(body));
                String user = exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF); observedUser.set(user);
                assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                if (user != null) GatewayTestKeys.user(user, "gateway");
                var request = verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest("auth", "POST", path, BodyHash.sha256(body), op, BodyHash.userProof(user))).proof();
                byte[] raw = answer.get().getBytes(StandardCharsets.UTF_8); long now = clock.instant().getEpochSecond();
                if (!unsigned.get()) exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF, auth.sign(
                        new WorkloadResponseProof(2, "auth", "gateway", request.nonce(), 200, BodyHash.sha256(raw), now, now + 30)));
                exchange.sendResponseHeaders(200, raw.length); exchange.getResponseBody().write(raw);
            } catch (Throwable error) { serverFailure.compareAndSet(null, error); }
            finally { exchange.close(); }
        }); server.start();
        var operations = new HashSet<>(AuthProtocolClient.OPERATIONS); operations.add(AuthProtocolClient.BROWSER_SESSION_OPERATION);
        transport = new SignedHttpClient("gateway", signer, verifier, clock, TimePolicy.defaults(),
                Map.of("auth", URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                Map.of("auth", operations), new SignedHttpClient.Limits(4096, 4096, 4, Duration.ofSeconds(1), Duration.ofSeconds(3)));
        gate = new AuthPermissionGate(new AuthProtocolClient(transport));
    }
    @AfterEach void close() throws Exception {
        transport.close(); server.stop(0); handlers.shutdownNow(); assertTrue(handlers.awaitTermination(3, TimeUnit.SECONDS));
        if (serverFailure.get() != null) throw new AssertionError("Gateway auth fixture failed", serverFailure.get());
    }
    String proof() { return GatewayTestKeys.identitySigner().sign(42L, "alice", 7, "gateway", "gate-1", Instant.now().plusSeconds(60)); }
    @Test void browserFamilyCheckUsesSeparateSignedGatewayCapabilityWithoutUserHeader() {
        String sid = UUID.randomUUID().toString();
        assertFalse(AuthProtocolClient.OPERATIONS.contains(AuthProtocolClient.BROWSER_SESSION_OPERATION));
        assertTrue(gate.browserSessionActive(42L, "alice", 7, sid).block());
        assertEquals("/internal/v2/browser-sessions/check", observedPath.get()); assertNull(observedUser.get());
        assertEquals(sid, observedBody.get().get("sid").textValue()); assertEquals(4, observedBody.get().size());
        answer.set("{\"active\":false}"); assertFalse(gate.browserSessionActive(42L, "alice", 7, sid).block());
        unsigned.set(true); assertThrows(RuntimeException.class, () -> gate.browserSessionActive(42L, "alice", 7, sid).block());
    }
    @Test void signedSessionChecksStableUserAndAccessVersion() {
        assertTrue(gate.sessionActive(42L, "alice", 7).block());
        assertEquals("/internal/v2/sessions/check", observedPath.get()); assertNull(observedUser.get());
        assertEquals(7L, observedBody.get().get("accessVersion").asLong());
        answer.set("{\"active\":false}"); assertFalse(gate.sessionActive(42L, "alice", 7).block());
    }
    @Test void permissionsCarryOriginalGatewayUserProofAndNoBodySubject() {
        answer.set("{\"allowed\":true,\"reason\":null}"); String proof = proof();
        assertTrue(gate.mayMutate(proof, "metadata").block()); assertEquals(proof, observedUser.get());
        assertEquals(1, observedBody.get().size()); assertEquals("metadata", observedBody.get().get("service").asText());
        answer.set("{\"allowed\":false,\"reason\":\"denied\"}"); assertFalse(gate.mayManage(proof).block());
        assertEquals(0, observedBody.get().size());
    }
    @Test void unsignedOrMalformedSuccessCannotAuthorize() {
        unsigned.set(true); assertThrows(RuntimeException.class, () -> gate.sessionActive(42L, "alice", 7).block());
        unsigned.set(false); answer.set("{\"active\":\"true\"}");
        assertThrows(RuntimeException.class, () -> gate.sessionActive(42L, "alice", 7).block());
    }
    @Test void callIsDeferredUntilSubscribed() {
        var result = gate.sessionActive(42L, "alice", 7); assertEquals(0, calls.get());
        assertTrue(result.block()); assertEquals(1, calls.get());
    }
}
