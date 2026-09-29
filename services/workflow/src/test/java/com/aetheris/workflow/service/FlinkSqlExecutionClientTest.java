package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

class FlinkSqlExecutionClientTest {

    @Test
    void executesSessionStatementAndClosesSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);

        server.expect(requestTo("http://flink:8083/v1/sessions"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"sessionHandle\":\"session-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-1/statements"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"operationHandle\":\"op-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-1/operations/op-1/status"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":{\"type\":\"FINISHED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-1/operations/op-1/close"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());
        server.expect(requestTo("http://flink:8083/v1/sessions/session-1"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());

        FlinkSqlExecutionClient.ExecutionResult result = client.execute(
                "http://flink:8083", "INSERT INTO sink SELECT 1", 5);
        assertEquals("op-1", result.operationHandle());
        server.verify();
    }

    @Test
    void rejectsMissingGatewayEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);
        assertThrows(FlinkSqlExecutionClient.FlinkExecutionException.class,
                () -> client.execute("", "SELECT 1", 5));
    }

    @Test
    void reportsMissingActiveOperationWithoutCallingGateway() {
        RestClient.Builder builder = RestClient.builder();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);
        assertEquals(false, client.cancel("attempt-404"));
    }

    @Test
    void readsGatewayInfoHealth() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);

        server.expect(requestTo("http://flink:8083/v1/info"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"version\":\"1.20.1\"}", MediaType.APPLICATION_JSON));

        FlinkSqlExecutionClient.GatewayHealth health = client.health("http://flink:8083");
        assertEquals("UP", health.status());
        assertEquals("1.20.1", health.version());
        assertEquals("http://flink:8083", health.endpoint());
        server.verify();
    }

    @Test
    void startsStreamingWithoutClosingSessionAndCancelsPersistedHandle() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);

        server.expect(requestTo("http://flink:8083/v1/sessions"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"sessionHandle\":\"session-stream\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-stream/statements"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"operationHandle\":\"op-stream\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-stream/operations/op-stream/cancel"))
                .andExpect(method(POST))
                .andRespond(withSuccess());

        FlinkSqlExecutionClient.ExecutionResult result = client.startStreaming(
                "http://flink:8083", "INSERT INTO sink SELECT * FROM source", "attempt-7");
        assertEquals("session-stream", result.sessionHandle());
        assertEquals("op-stream", result.operationHandle());

        assertEquals(true, client.cancelHandle("http://flink:8083", "session-stream", "op-stream"));
        server.verify();
    }

    @Test
    void readsActualGatewayJobIdAfterInsertSubmission() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);
        server.expect(requestTo("http://flink:8083/v1/sessions/s-1/operations/o-1/result/0?rowFormat=JSON"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"jobID\":\"0288779de388defe9e649cfb66b691c8\",\"results\":{\"data\":[]}}",
                        MediaType.APPLICATION_JSON));

        assertEquals("0288779de388defe9e649cfb66b691c8",
                client.submittedJobId("http://flink:8083", "s-1", "o-1"));
        server.verify();
    }

    @Test
    void submitsSetupAndStreamingInsertInOneGatewaySession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);
        server.expect(requestTo("http://flink:8083/v1/sessions"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"sessionHandle\":\"s-setup\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/s-setup/statements"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"operationHandle\":\"o-setup\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/s-setup/operations/o-setup/status"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":\"FINISHED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/s-setup/operations/o-setup/close"))
                .andExpect(method(DELETE)).andRespond(withSuccess());
        server.expect(requestTo("http://flink:8083/v1/sessions/s-setup/statements"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"operationHandle\":\"o-stream\"}", MediaType.APPLICATION_JSON));

        var result = client.startStreaming("http://flink:8083",
                "CREATE TEMPORARY TABLE src (id BIGINT) WITH ('connector'='datagen'); "
                        + "INSERT INTO sink SELECT id FROM src", "attempt-1");

        assertEquals("s-setup", result.sessionHandle());
        assertEquals("o-stream", result.operationHandle());
        server.verify();
    }

    @Test
    void startsStreamingFromCompletedSavepointBeforeReturningHandle() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(builder, Duration.ofSeconds(1), 100);

        server.expect(requestTo("http://flink:8083/v1/sessions"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"sessionHandle\":\"session-resume\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-resume/statements"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"operationHandle\":\"op-set\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-resume/operations/op-set/status"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":{\"type\":\"FINISHED\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8083/v1/sessions/session-resume/operations/op-set/close"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess());
        server.expect(requestTo("http://flink:8083/v1/sessions/session-resume/statements"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"operationHandle\":\"op-resume\"}", MediaType.APPLICATION_JSON));

        FlinkSqlExecutionClient.ExecutionResult result = client.startStreamingFromSavepoint(
                "http://flink:8083", "INSERT INTO sink SELECT * FROM source",
                "s3://warehouse/savepoints/sp-1", "attempt-resume");
        assertEquals("session-resume", result.sessionHandle());
        assertEquals("op-resume", result.operationHandle());
        server.verify();
    }

    @Test
    void rejectsUnsafeSavepointPathBeforeCallingGateway() {
        FlinkSqlExecutionClient client = new FlinkSqlExecutionClient(
                RestClient.builder(), Duration.ofSeconds(1), 100);
        assertThrows(FlinkSqlExecutionClient.FlinkExecutionException.class, () ->
                client.startStreamingFromSavepoint("http://flink:8083", "INSERT INTO sink SELECT 1",
                        "http://not-allowed/sp", "attempt-resume"));
    }
}
