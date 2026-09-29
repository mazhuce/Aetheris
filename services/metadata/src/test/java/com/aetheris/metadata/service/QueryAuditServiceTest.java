package com.aetheris.metadata.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class QueryAuditServiceTest {
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
