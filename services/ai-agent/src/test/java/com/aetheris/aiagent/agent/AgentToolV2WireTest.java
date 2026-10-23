package com.aetheris.aiagent.agent;
import com.aetheris.aiagent.llm.*;
import com.aetheris.aiagent.security.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class AgentToolV2WireTest {
    @Test void allFiveFixedPostsBindExactUserAndBody() throws Exception {
        try(var fixture=new AiMetadataFixture()){
            fixture.attach("alice");String user=AiProofContext.current().compact();
            assertFalse(fixture.client.searchTables("orders","dwd").isEmpty());assertFalse(fixture.client.describeTable(8L).containsKey("error"));
            assertFalse(fixture.client.query(9L,"SELECT 7").containsKey("error"));assertFalse(fixture.client.searchMetrics("gmv").isEmpty());
            assertFalse(fixture.client.queryMetric("GMV",List.of("country"),"day","2026-01-01","2026-01-31").containsKey("error"));
            assertEquals(5,fixture.requests.size());assertTrue(fixture.requests.stream().allMatch(r->r.userProof().equals(user)));
            assertEquals("dwd",fixture.requests.getFirst().body().get("layer").asText());
            assertEquals(100,fixture.requests.getLast().body().path("query").path("limit").asInt());
            assertEquals("GMV",fixture.requests.getLast().body().path("metricCode").asText());
        }
    }
    @Test void missingContextCancelledOrExhaustedRunAndExpiredProofHaveZeroHttp() throws Exception {
        var now=new AtomicLong(Clock.systemUTC().instant().getEpochSecond());
        var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochSecond(now.get());}};
        try(var fixture=new AiMetadataFixture(new AiTestKeys(clock))){
            assertTrue(fixture.client.query(9L,"SELECT 7").containsKey("error"));
            fixture.attach("alice");AiRunContext.current().cancel();assertTrue(fixture.client.query(9L,"SELECT 7").containsKey("error"));
            fixture.attach("alice");var run=new AiRunContext(new AiBudgetLimits(1,0,1000,100,1000,1000,1,0));AiRunContext.attach(run);
            assertThrows(LlmException.class,run::beforeTool);assertTrue(fixture.client.query(9L,"SELECT 7").containsKey("error"));
            fixture.attach("alice");now.addAndGet(61);assertTrue(fixture.client.query(9L,"SELECT 7").containsKey("error"));
            assertTrue(fixture.requests.isEmpty());
        }
    }
    @Test void untrustedWrongNonceDeniedAndDependencyFailureCannotYieldData() throws Exception {
        try(var fixture=new AiMetadataFixture()){
            fixture.attach("alice");for(String fault:List.of("unsigned","tampered","nonce","denied","signed500")){
                fixture.fault=fault;assertTrue(fixture.client.query(9L,"SELECT 7").containsKey("error"));
            }assertEquals(5,fixture.requests.size());
        }
    }
    @Test void incompleteArgumentsFailSafelyBeforeAnyHttp() throws Exception {
        try(var fixture=new AiMetadataFixture()){
            fixture.attach("alice");assertTrue(fixture.client.describeTable(null).containsKey("error"));
            assertTrue(fixture.client.query(null,"SELECT 7").containsKey("error"));assertTrue(fixture.client.query(9L,null).containsKey("error"));
            assertTrue(fixture.client.queryMetric(null,List.of(),null,null,null).containsKey("error"));assertTrue(fixture.requests.isEmpty());
        }
    }
}
