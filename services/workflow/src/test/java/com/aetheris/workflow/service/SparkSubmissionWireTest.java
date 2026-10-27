package com.aetheris.workflow.service;

import com.aetheris.workflow.service.ExecutionAdapter.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Two actual loopback runner origins; POST counts prove that recovery never submits again. */
class SparkSubmissionWireTest {
    private static final String KEY = "attempt-13-f2", HASH = "a".repeat(64);
    private static final String ID = "1".repeat(32), OTHER = "2".repeat(32);
    private Endpoint normal, selected;
    private SparkBatchExecutionAdapter adapter;

    @BeforeEach void setup() throws Exception {
        normal = new Endpoint(); selected = new Endpoint(); normal.getStatus = 200; normal.getBody = handle(OTHER, KEY, HASH, "running");
        selected.postBody = handle(ID, KEY, HASH, "running");
        adapter = new SparkBatchExecutionAdapter(RestClient.builder(), normal.origin(), "t".repeat(32), true,
                Duration.ofSeconds(1), new OutboundTargetPolicy(), selected.origin());
    }
    @AfterEach void close() { if (normal != null) normal.close(); if (selected != null) selected.close(); }
    private Submission input() { return new Submission(KEY, "approved.py", "pyspark", HASH, null,
            Map.of("bizDate", "2024-03-02", "attemptId", 13), 1, 512, 30); }
    private static String handle(String id, String key, String digest, String state) {
        return "{\"id\":\"" + id + "\",\"requestKey\":\"" + key + "\",\"status\":\"" + state
                + "\",\"applicationId\":null,\"artifactDigest\":\"" + digest + "\"}";
    }
    private SparkRunnerException unknown() {
        var error = assertThrows(SparkRunnerException.class, () -> adapter.submit(selected.origin(), input()));
        assertTrue(error.isSubmissionOutcomeUnknown()); assertEquals(1, selected.posts.get());
        assertEquals(0, normal.posts.get()); assertEquals(0, normal.gets.get()); return error;
    }
    @Test void successfulSubmitIsBoundToTheExactKeyArtifactAndSelectedRunner() {
        var result = adapter.submit(selected.origin(), input()); assertEquals(ID, result.id());
        assertEquals(KEY, result.requestKey()); assertEquals(HASH, result.artifactDigest());
        assertEquals(1, selected.posts.get()); assertEquals(0, selected.gets.get()); assertEquals(0, normal.gets.get());
    }
    @Test void serverFailureWithoutReceiptStaysUnknownAndCannotReleaseCapacityAsKnownFailure() {
        selected.postStatus = 503; selected.postBody = "{\"message\":\"jdbc:password-in-error\"}";
        var failure = unknown(); assertFalse(failure.getMessage().contains("password")); assertEquals(1, selected.gets.get());
    }
    @Test void serverFailureWithExistingReceiptRecoversFromTheSameRunnerWithoutAnotherPost() {
        selected.postStatus = 500; selected.getStatus = 200; selected.getBody = handle(ID, KEY, HASH, "running");
        assertEquals(ID, adapter.submit(selected.origin(), input()).id());
        assertEquals(1, selected.posts.get()); assertEquals(1, selected.gets.get()); assertEquals(0, normal.gets.get());
    }
    @Test void lostResponseNeverSwitchesToTheDefaultRunnerEvenIfItHasTheSameKey() {
        selected.dropPost = true; selected.getStatus = 200; selected.getBody = handle(ID, KEY, HASH, "running");
        assertEquals(ID, adapter.submit(selected.origin(), input()).id());
        assertEquals(1, selected.posts.get()); assertEquals(1, selected.gets.get()); assertEquals(0, normal.gets.get());
    }
    @Test void missingReceiptAfterLostResponseKeepsUnknownAndOnePost() {
        selected.dropPost = true; unknown(); assertEquals(1, selected.gets.get());
    }
    @Test void malformedEmptyWrongKeyDigestIdAndStatusResponsesCannotProveSubmission() {
        for (String body : new String[]{"", "{bad", "null", handle(ID, "another-key", HASH, "running"),
                handle(ID, KEY, "b".repeat(64), "running"), handle("bad/../../id", KEY, HASH, "running"),
                handle(ID, KEY, HASH, "invented")}) {
            selected.posts.set(0); selected.gets.set(0); selected.postBody = body;
            unknown(); assertEquals(1, selected.gets.get());
        }
    }
    @Test void duplicateAndOversizedResponsesRemainUnknown() {
        for (String body : new String[]{handle(ID, KEY, HASH, "running").replace("\"id\":", "\"id\":\"" + OTHER + "\",\"id\":"),
                handle(ID, KEY, HASH, "running").replace("\"applicationId\":null", "\"applicationId\":\"" + "x".repeat(70_000) + "\"")}) {
            selected.posts.set(0); selected.gets.set(0); selected.postBody = body;
            unknown(); assertEquals(1, selected.gets.get());
        }
    }
    @Test void recoveredWrongKeyOrArtifactNeverBecomesAnAcceptedHandle() {
        for (String body : new String[]{handle(ID, "other", HASH, "running"), handle(ID, KEY, "b".repeat(64), "running")}) {
            selected.posts.set(0); selected.gets.set(0); selected.dropPost = true; selected.getStatus = 200; selected.getBody = body;
            unknown(); assertEquals(1, selected.gets.get());
        }
    }
    @Test void explicitPreExecutionClientRejectionsAreKnownAndDoNotReadOrLeakRemoteDetails() {
        for (int status : new int[]{400, 401, 403, 404, 405, 413, 415, 422}) {
            selected.posts.set(0); selected.postStatus = status; selected.postBody = "{\"message\":\"password=private-token\"}";
            var failure = assertThrows(SparkRunnerException.class, () -> adapter.submit(selected.origin(), input()));
            assertFalse(failure.isSubmissionOutcomeUnknown()); assertFalse(failure.getMessage().contains("private-token"));
            assertEquals(1, selected.posts.get()); assertEquals(0, selected.gets.get()); assertEquals(0, normal.gets.get());
        }
    }
    @Test void timeoutConflictRateLimitAndRedirectResponsesCannotProveNonExecution() {
        for (int status : new int[]{408, 409, 425, 429, 302}) {
            selected.posts.set(0); selected.gets.set(0); selected.postStatus = status;
            unknown(); assertEquals(1, selected.gets.get());
        }
    }
    @Test void byRequestLookupRejectsAnotherKeyAndNeverPosts() {
        selected.getStatus = 200; selected.getBody = handle(ID, "wrong-key", HASH, "running");
        assertThrows(SparkRunnerException.class, () -> adapter.reconcile(selected.origin(), KEY));
        assertEquals(0, selected.posts.get()); assertEquals(1, selected.gets.get()); assertEquals(0, normal.gets.get());
    }
    @Test void statusRejectsWrongHandleKeyArtifactAndMalformedCounts() {
        for(String body:new String[]{handle(OTHER,KEY,HASH,"succeeded"),handle(ID,"wrong",HASH,"succeeded"),handle(ID,KEY,"b".repeat(64),"succeeded"),handle(ID,KEY,HASH,"succeeded").replace("\"applicationId\":null","\"affectedRows\":-1,\"applicationId\":null")}){
            selected.getStatus=200;selected.getBody=body;assertThrows(SparkRunnerException.class,()->adapter.statusBound(selected.origin(),ID,KEY,HASH));
        }
        assertEquals(0,selected.posts.get());assertEquals(0,normal.gets.get());
    }
    @Test void cancellationRequiresAValidTerminalConfirmationAndOriginalBinding(){
        selected.getStatus=200;
        for(String time:new String[]{"null","\"bad-time\"","\"2999-01-01T00:00:00Z\""}){selected.getBody=handle(ID,KEY,HASH,"cancelled").replace("\"applicationId\":null","\"cancellationConfirmedAt\":"+time+",\"applicationId\":null");assertThrows(SparkRunnerException.class,()->adapter.cancelBound(selected.origin(),ID,KEY,HASH));}
        selected.getBody=handle(ID,KEY,HASH,"cancelled").replace("\"applicationId\":null","\"cancellationConfirmedAt\":\"2026-10-25T00:00:00Z\",\"applicationId\":null");assertEquals("cancelled",adapter.cancelBound(selected.origin(),ID,KEY,HASH).status());assertEquals(0,selected.posts.get());
    }
    @Test void logPagesRequireOriginalIdentityAndBoundedForwardCursors(){
        selected.getStatus=200;String valid="{\"id\":\""+ID+"\",\"requestKey\":\""+KEY+"\",\"artifactDigest\":\""+HASH+"\",\"content\":\"ok\",\"nextOffset\":12,\"eof\":true}";
        selected.getBody=valid;assertEquals(12,adapter.logsBound(selected.origin(),ID,KEY,HASH,10,32).nextOffset());
        for(String body:new String[]{valid.replace(KEY,"wrong"),valid.replace(HASH,"b".repeat(64)),valid.replace("\"nextOffset\":12","\"nextOffset\":9"),valid.replace("\"nextOffset\":12","\"nextOffset\":1000"),valid.replace("\"eof\":true","\"eof\":\"true\"")}){selected.getBody=body;assertThrows(SparkRunnerException.class,()->adapter.logsBound(selected.origin(),ID,KEY,HASH,10,32));}
        assertEquals(0,selected.posts.get());
    }

    private static final class Endpoint implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger posts = new AtomicInteger(), gets = new AtomicInteger();
        volatile int postStatus = 202, getStatus = 404;
        volatile String postBody = "{}", getBody = "{}";
        volatile boolean dropPost;
        Endpoint() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                boolean post = "POST".equals(exchange.getRequestMethod());
                if (post) posts.incrementAndGet(); else gets.incrementAndGet();
                if (post && dropPost) { exchange.close(); return; }
                byte[] raw = (post ? postBody : getBody).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (postStatus == 302) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
                exchange.sendResponseHeaders(post ? postStatus : getStatus, raw.length);
                try (var output = exchange.getResponseBody()) { output.write(raw); }
                finally { exchange.close(); }
            });
            server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); }
    }
}
