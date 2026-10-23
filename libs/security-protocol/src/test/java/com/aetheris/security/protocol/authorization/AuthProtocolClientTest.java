package com.aetheris.security.protocol.authorization;

import com.aetheris.security.protocol.*;
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

class AuthProtocolClientTest {
    final Clock clock = Clock.systemUTC();
    final ObjectMapper json = new ObjectMapper();
    ProtocolSigner gateway, auth, user;
    ProtocolVerifier verifier;
    HttpServer server;
    SignedHttpClient transport;
    AuthProtocolClient client;
    ExecutorService handlers;
    final AtomicReference<String> response = new AtomicReference<>("{\"allowed\":true,\"reason\":null}");
    final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>(), receivedUser = new AtomicReference<>();
    final AtomicReference<com.fasterxml.jackson.databind.JsonNode> body = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
    final AtomicBoolean unsigned = new AtomicBoolean();
    @BeforeEach void setup() throws Exception {
        var gen = KeyPairGenerator.getInstance("Ed25519"); var g = gen.generateKeyPair(); var a = gen.generateKeyPair(); var u = gen.generateKeyPair();
        var keys = new KeyRegistry(List.of(new KeyRegistry.Entry("g", KeyPurpose.WORKLOAD, "gateway", g.getPublic()),
                new KeyRegistry.Entry("a", KeyPurpose.WORKLOAD, "auth", a.getPublic()), new KeyRegistry.Entry("u", KeyPurpose.USER, "gateway", u.getPublic())));
        gateway = new ProtocolSigner("g", KeyPurpose.WORKLOAD, "gateway", g.getPrivate(), keys, clock, TimePolicy.defaults());
        auth = new ProtocolSigner("a", KeyPurpose.WORKLOAD, "auth", a.getPrivate(), keys, clock, TimePolicy.defaults());
        user = new ProtocolSigner("u", KeyPurpose.USER, "gateway", u.getPrivate(), keys, clock, TimePolicy.defaults());
        verifier = new ProtocolVerifier(keys, clock, TimePolicy.defaults());
        handlers = Executors.newVirtualThreadPerTaskExecutor(); server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers); server.createContext("/", exchange -> {
            try {
                calls.incrementAndGet(); String actualPath = exchange.getRequestURI().getRawPath(); path.set(actualPath);
                String op = switch (actualPath) {
                    case "/internal/v2/sessions/check" -> "auth.session.check";
                    case "/internal/v2/permissions/check" -> "auth.permission.check";
                    case "/internal/v2/permissions/visibility" -> "auth.permission.visibility";
                    case "/internal/v2/permissions/check-batch" -> "auth.permission.batch";
                    case "/internal/v2/permissions/may-manage" -> "auth.permission.manage";
                    case "/internal/v2/permissions/may-mutate" -> "auth.permission.mutate";
                    case "/internal/v2/permissions/version" -> "auth.permission.version";
                    default -> throw new AssertionError("Unexpected auth route");
                };
                assertNull(exchange.getRequestURI().getRawQuery()); assertEquals("POST", exchange.getRequestMethod());
                assertNull(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                byte[] bytes = exchange.getRequestBody().readAllBytes(); body.set(json.readTree(bytes));
                String compact = exchange.getRequestHeaders().getFirst(SignedHttpClient.USER_PROOF); receivedUser.set(compact);
                var request = verifier.verifyRequest(exchange.getRequestHeaders().getFirst(SignedHttpClient.REQUEST_PROOF),
                        new ExpectedRequest("auth", "POST", actualPath, BodyHash.sha256(bytes), op, BodyHash.userProof(compact))).proof();
                assertEquals("gateway", request.caller());
                if (compact != null) verifier.verifyUser(compact, "gateway");
                byte[] answer = response.get().getBytes(StandardCharsets.UTF_8); long now = clock.instant().getEpochSecond();
                if (!unsigned.get()) exchange.getResponseHeaders().set(SignedHttpClient.RESPONSE_PROOF,
                        auth.sign(new WorkloadResponseProof(2, "auth", "gateway", request.nonce(), status.get(), BodyHash.sha256(answer), now, now + 30)));
                exchange.sendResponseHeaders(status.get(), answer.length); exchange.getResponseBody().write(answer);
            } catch (Throwable failure) { serverFailure.compareAndSet(null, failure); }
            finally { exchange.close(); }
        }); server.start();
        transport = new SignedHttpClient("gateway", gateway, verifier, clock, TimePolicy.defaults(),
                Map.of("auth", URI.create("http://127.0.0.1:" + server.getAddress().getPort())), Map.of("auth", AuthProtocolClient.OPERATIONS),
                new SignedHttpClient.Limits(1024 * 1024, 1024 * 1024, 8, Duration.ofSeconds(1), Duration.ofSeconds(3)));
        client = new AuthProtocolClient(transport);
    }
    @AfterEach void stop() throws Exception {
        transport.close(); server.stop(0); handlers.shutdownNow(); assertTrue(handlers.awaitTermination(3, TimeUnit.SECONDS));
        if (serverFailure.get() != null) throw new AssertionError("Auth wire fixture failed", serverFailure.get());
    }
    String proof() { long now = clock.instant().getEpochSecond(); return user.sign(new UserProof(2, "gateway", "gateway", 42, "alice", 7, now, now + 120, "auth-wire")); }
    @Test void liveSessionUsesSignedWorkloadAndStableVersionWithoutUserHeader() {
        response.set("{\"active\":true}"); assertTrue(client.sessionActive(42, "alice", 7).join());
        assertEquals(7, body.get().get("accessVersion").asLong()); assertEquals(42, body.get().get("uid").asLong()); assertNull(receivedUser.get());
    }
    @Test void permissionOperationsForwardExactProofWithoutBodySubject() {
        String proof = proof(); assertTrue(client.check(proof, "datasource", 3L, "query", Map.of()).join().allowed());
        assertFalse(body.get().has("username")); assertFalse(body.get().has("uid")); assertEquals(proof, receivedUser.get());
        assertTrue(client.mayManage(proof).join().allowed()); assertEquals(0, body.get().size());
        assertTrue(client.mayMutate(proof, "metadata").join().allowed()); assertEquals(Set.of("service"), fieldNames(body.get()));
    }
    @Test void visibilityBatchAndVersionValidateTheirResponseContracts() {
        response.set("{\"mode\":\"IDS\",\"ids\":[3,4]}"); assertEquals(List.of(3L, 4L), client.visibility(proof(), "table", "read").join().ids());
        response.set("{\"allowedIds\":[3]}"); assertEquals(Set.of(3L), client.checkBatch(proof(), "table", "read", List.of(new AuthProtocolClient.Item(3, Map.of()))).join());
        response.set("{\"version\":17}"); assertEquals(17L, client.version().join()); assertNull(receivedUser.get());
    }
    @Test void signedMalformedOrAmbiguousDecisionNeverBecomesPermission() {
        for (String value : List.of("{\"allowed\":\"true\",\"reason\":null}", "{\"allowed\":true}",
                "{\"allowed\":true,\"allowed\":false,\"reason\":null}", "{\"allowed\":true,\"reason\":null,\"role\":\"admin\"}",
                "{\"allowed\":true,\"reason\":null} {}", "[]", "null")) {
            response.set(value); assertThrows(CompletionException.class, () -> client.mayManage(proof()).join());
        }
    }
    @Test void signedBatchCannotReturnOtherOrDuplicateResourceIds() {
        for (String value : List.of("{\"allowedIds\":[9]}", "{\"allowedIds\":[3,3]}", "{\"allowedIds\":[-1]}")) {
            response.set(value); assertThrows(CompletionException.class, () -> client.checkBatch(proof(), "table", "read", List.of(new AuthProtocolClient.Item(3, Map.of()))).join());
        }
    }
    @Test void unsignedSuccessAndSignedErrorsNeverAuthorize() {
        unsigned.set(true); assertThrows(CompletionException.class, () -> client.mayManage(proof()).join());
        unsigned.set(false);
        for (int code : List.of(403, 409, 500, 503)) { status.set(code); assertThrows(CompletionException.class, () -> client.mayManage(proof()).join()); }
    }
    @Test void malformedSessionTypesAndNonBooleanTruthAreRejected() {
        for (String value : List.of("{\"active\":1}", "{\"active\":true,\"role\":\"admin\"}", "{}")) {
            response.set(value); assertThrows(CompletionException.class, () -> client.sessionActive(42, "alice", 7).join());
        }
    }
    @Test void invalidCallerInputDoesNotMakeANetworkRequest() {
        assertThrows(RuntimeException.class, () -> client.check(null, "table", 3L, "read", Map.of()));
        assertThrows(RuntimeException.class, () -> client.sessionActive(0, "alice", 0));
        assertThrows(RuntimeException.class, () -> client.sessionActive(42, "alice", -1));
        assertThrows(RuntimeException.class, () -> client.check(proof(), "table", 3L, "read", Map.of("bad key", "x")));
        assertThrows(RuntimeException.class, () -> client.checkBatch(proof(), "table", "read", List.of(new AuthProtocolClient.Item(3, Map.of()), new AuthProtocolClient.Item(3, Map.of()))));
        assertEquals(0, calls.get());
    }
    Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode node) { var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }
}
