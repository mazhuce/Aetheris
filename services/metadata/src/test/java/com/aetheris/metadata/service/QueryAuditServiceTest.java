package com.aetheris.metadata.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class QueryAuditServiceTest {
    @org.junit.jupiter.api.BeforeEach void trustedActor() throws Exception {
        var proof = new com.aetheris.metadata.security.MetadataProofFixture();
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/metadata/query");
        request.addHeader(com.aetheris.security.protocol.transport.SignedHttpClient.USER_PROOF, proof.user("metadata"));
        proof.direct((uid,name,av)->true).doFilter(request, new org.springframework.mock.web.MockHttpServletResponse(), (req,res)->
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                        new org.springframework.web.context.request.ServletRequestAttributes((jakarta.servlet.http.HttpServletRequest)req)));
    }
    @org.junit.jupiter.api.AfterEach void resetContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test void onlyVerifiedMatchingUserCanProvideTheStoredRequestId() throws Exception {
        var proof = new com.aetheris.metadata.security.MetadataProofFixture();
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST","/api/metadata/query");
        request.addHeader(com.aetheris.security.protocol.transport.SignedHttpClient.USER_PROOF,proof.user("metadata"));
        request.addHeader("X-Request-Id","forged-request");
        var jdbc = mock(JdbcTemplate.class);
        var service = new QueryAuditService(jdbc);
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        proof.direct((uid,name,av)->true).doFilter(request,response,(req,res)->{
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes((jakarta.servlet.http.HttpServletRequest)req));
            service.record("adhoc",null,null,11L,"alice","success",1L,1L,false,null,null,"select 1");
            assertThrows(IllegalStateException.class,()->service.record("adhoc",null,null,11L,"another-user","success",1L,1L,false,null,null,"select 1"));
        });
        assertEquals(200,response.getStatus());
        var calls = mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).toList();
        assertEquals(1,calls.size());
        assertTrue(((String)calls.getFirst().getArgument(0)).contains("request_id"));
        var values=(Object[])calls.getFirst().getRawArguments()[1];
        assertEquals("metadata-v2-test",values[12]); assertEquals(42L,values[13]);
    }

    @Test void driverMessagesAndUnknownCodesAreNotPersistedAsAuditDetails() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new QueryAuditService(jdbc);
        service.record("adhoc",null,null,11L,"alice","failed",null,1L,false,
                "secret-canary-code","password=secret-canary; jdbc:postgresql://private-host/db select 'secret-canary'", "select 'secret-canary'");
        var invocation = mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).findFirst().orElseThrow();
        var values = (Object[])invocation.getRawArguments()[1];
        assertEquals("QUERY_FAILED",values[9]);
        assertEquals("Query failed; correlate with requestId",values[10]);
        for (Object value : values) assertFalse(value instanceof String s && s.contains("secret-canary"));
        assertEquals(64,((String)values[11]).length());
    }

    @Test void untrustedRequestIdHeaderIsNeverAnAuditFallback() {
        var jdbc = mock(JdbcTemplate.class);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Request-Id","forged-id");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        assertThrows(IllegalStateException.class,()->new QueryAuditService(jdbc).record("adhoc",null,null,11L,"alice","success",1L,1L,false,null,null,"select 1"));
        verifyNoInteractions(jdbc);
    }

    @Test void auditInsertFailuresRemainVisibleWithoutInflatingRecordedQueryCounts() {
        var jdbc = mock(JdbcTemplate.class);
        var metrics = new SimpleMeterRegistry();
        var service = new QueryAuditService(jdbc,metrics);
        when(jdbc.update(anyString(),any(Object[].class))).thenThrow(new IllegalStateException("fixture store failure"));
        assertThrows(IllegalStateException.class,()->service.record("adhoc",null,null,11L,"alice","success",1L,1L,false,null,null,"select 1"));
        assertThrows(IllegalStateException.class,()->service.recordPublished("published_api","ASSET",1L,11L,"alice","success",1L,1L,"select 1",1L,java.util.UUID.randomUUID().toString(),"request-1",42L));
        assertEquals(2.0,metrics.get("aetheris.query.audit.persist.failed").counter().count());
        assertEquals(0.0,metrics.get("aetheris.query.audit.count").counter().count());
        // Percentile gauges add fixed phi/quantile labels; no user, SQL or request values may become labels.
        assertTrue(metrics.getMeters().stream().flatMap(meter->meter.getId().getTags().stream())
                .allMatch(tag->java.util.Set.of("phi","quantile").contains(tag.getKey())));
    }
    @Test
    void recordPublishesLowCardinalityQueryMetrics() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryAuditService service = new QueryAuditService(jdbc, registry);

        service.record("semantic", "sales", 7L, 11L, "alice", "success", 4L,
                3200L, false, null, null, "select 1");
        service.record("adhoc", null, null, 11L, "alice", "failed", null,
                50L, false, "TIMEOUT", "timeout", "select 2");

        assertEquals(2.0, registry.get("aetheris.query.audit.count").counter().count());
        assertEquals(1.0, registry.get("aetheris.query.audit.failed").counter().count());
        assertEquals(1.0, registry.get("aetheris.query.audit.slow").counter().count());
        assertEquals(3250L, registry.get("aetheris.query.audit.duration").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    void recordDoesNotCreateDurationForMissingOrNegativeDuration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        QueryAuditService service = new QueryAuditService(jdbc, registry);

        service.record("semantic", null, null, null, "alice", "success", null,
                null, false, null, null, null);
        service.record("semantic", null, null, null, "alice", "success", null,
                -1L, false, null, null, null);

        assertEquals(0L, registry.get("aetheris.query.audit.duration").timer().count());
        assertEquals(0.0, registry.get("aetheris.query.audit.slow").counter().count());
    }
}
