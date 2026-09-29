package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.PATCH;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FlinkJobManagerClientTest {
    @Test
    void readsStatusCheckpointAndSavepointLifecycle() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FlinkJobManagerClient client = new FlinkJobManagerClient(builder.baseUrl("http://flink:8081").build());

        server.expect(requestTo("http://flink:8081/jobs/job-1")).andExpect(method(GET))
                .andRespond(withSuccess("{\"state\":\"RUNNING\",\"name\":\"cdc\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8081/jobs/job-1/checkpoints")).andExpect(method(GET))
                .andRespond(withSuccess("{\"latest\":{\"completed\":{\"id\":42,\"status\":\"COMPLETED\",\"trigger_timestamp\":100,\"latest_ack_timestamp\":120}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8081/jobs/job-1/savepoints")).andExpect(method(POST))
                .andExpect(content().json("{\"targetDirectory\":\"s3://warehouse/savepoints\",\"cancelJob\":false}"))
                .andRespond(withSuccess("{\"request-id\":\"request-1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8081/jobs/job-1/savepoints/request-1")).andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":{\"id\":\"COMPLETED\"},\"operation\":{\"location\":\"s3://warehouse/sp-1\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8081/jobs/job-1/stop")).andExpect(method(POST))
                .andExpect(content().json("{\"targetDirectory\":\"s3://warehouse/stop-savepoints\",\"drain\":true,\"formatType\":\"CANONICAL\"}"))
                .andRespond(withSuccess("{\"request-id\":\"request-stop\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://flink:8081/jobs/job-1")).andExpect(method(PATCH))
                .andRespond(withSuccess());

        assertEquals("RUNNING", client.status("job-1").state());
        assertEquals(42L, client.checkpoints("job-1").latestCompletedId());
        FlinkJobManagerClient.TriggeredOperation operation = client.triggerSavepoint("job-1", "s3://warehouse/savepoints", false);
        assertEquals("request-1", operation.requestId());
        assertEquals("s3://warehouse/sp-1", client.savepointStatus("job-1", "request-1").location());

        assertEquals("request-stop", client.stopWithSavepoint("job-1", "s3://warehouse/stop-savepoints", true).requestId());

        client.cancel("job-1");
        server.verify();
    }

    @Test
    void rejectsUnsafeJobAndTargetInputsBeforeNetworkCall() {
        RestClient.Builder builder = RestClient.builder();
        FlinkJobManagerClient client = new FlinkJobManagerClient(builder.baseUrl("http://flink:8081").build());

        assertThrows(IllegalArgumentException.class, () -> client.status("job/1"));
        assertThrows(IllegalArgumentException.class, () -> client.triggerSavepoint("job-1", "http://external/path", false));
    }
}
