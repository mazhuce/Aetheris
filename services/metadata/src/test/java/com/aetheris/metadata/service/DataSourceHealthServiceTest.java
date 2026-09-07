package com.aetheris.metadata.service;

import com.aetheris.metadata.dto.ConnectionHealth;
import com.aetheris.metadata.entity.DataSource;
import com.aetheris.metadata.entity.DataSourceHealthObservation;
import com.aetheris.metadata.mapper.DataSourceHealthMapper;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DataSourceHealthServiceTest {
    private final DataSourceHealthMapper mapper = mock(DataSourceHealthMapper.class);
    private final JdbcService jdbc = mock(JdbcService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T06:00:00Z"), ZoneOffset.UTC);
    private final OffsetDateTime now = OffsetDateTime.now(clock);

    @Test
    void storedActiveWithoutProbeIsUnknownAndListNeverConnects() {
        DataSource source = source();
        source.setStatus("active");
        when(mapper.findAll(List.of(5L))).thenReturn(List.of());
        try (var ignored = closeable(service(100))) {
            DataSourceHealthService service = ignored.service;
            assertEquals("unknown", service.attach(List.of(source)).getFirst().getConnectionHealth().status());
            verifyNoInteractions(jdbc);
        }
    }

    @Test
    void expiredSuccessBecomesUnknownAndRetainsPreviousEvidence() {
        try (var closeable = closeable(service(100))) {
            DataSourceHealthObservation observation = observation("available", now.minusSeconds(61), now.minusSeconds(1), 0);
            ConnectionHealth health = closeable.service.effective(source(), observation);
            assertEquals("unknown", health.status());
            assertEquals(observation.checkedAt(), health.checkedAt());
            assertEquals(observation.lastSuccessAt(), health.lastSuccessAt());
        }
    }

    @Test
    void strandedCheckingExpiresAndChangedConfigurationCannotReuseSuccess() {
        try (var closeable = closeable(service(100))) {
            assertEquals("unknown", closeable.service.effective(source(),
                    observation("checking", now.minusSeconds(20), now.minusSeconds(1), 0)).status());
            assertEquals(ConnectionHealth.unknown(), closeable.service.effective(source(),
                    observation("available", now, now.plusSeconds(60), 1)));
        }
    }

    @Test
    void completedFailureRetainsLastSuccessAndOnlyPersistsSafeMessage() throws Exception {
        when(mapper.claim(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jdbc.probe(any(), anyInt())).thenThrow(new SQLException("password=SECRET jdbc:hive2://internal:10000", "28P01"));
        when(mapper.complete(anyLong(), anyLong(), anyString(), anyString(), any(), any(), anyLong(), anyString(), anyString())).thenReturn(1);
        when(mapper.find(5L)).thenReturn(new DataSourceHealthObservation(5L, 0L, "unavailable", null,
                now, now, now.minusMinutes(1), now.plusMinutes(1), 2L,
                "AUTHENTICATION_FAILED", "认证失败，请检查用户名、密码与认证方式"));
        try (var closeable = closeable(service(1000))) {
            Map<String, Object> response = closeable.service.test(source());
            assertEquals(false, response.get("success"));
            assertEquals("AUTHENTICATION_FAILED", response.get("failureCode"));
            assertFalse(response.toString().contains("SECRET"));
            assertFalse(response.toString().contains("internal:10000"));
            assertEquals(now.minusMinutes(1), ((ConnectionHealth) response.get("connectionHealth")).lastSuccessAt());
            verify(mapper).complete(eq(5L), eq(0L), anyString(), eq("unavailable"), eq(now), eq(now.plusSeconds(60)),
                    anyLong(), eq("AUTHENTICATION_FAILED"), eq("认证失败，请检查用户名、密码与认证方式"));
        }
    }

    @Test
    void lateCompletionCannotReportSuccessAfterConfigurationChange() throws Exception {
        when(mapper.claim(anyLong(), anyLong(), anyString(), any(), any())).thenReturn(1);
        when(jdbc.probe(any(), anyInt())).thenReturn(Map.of());
        // The database CAS rejected a completion with an old revision/token.
        when(mapper.find(5L)).thenReturn(observation("unknown", null, null, 1));
        try (var closeable = closeable(service(1000))) {
            Map<String, Object> response = closeable.service.test(source());
            assertEquals(false, response.get("success"));
            assertEquals("PROBE_SUPERSEDED", response.get("failureCode"));
            assertEquals("unknown", ((ConnectionHealth) response.get("connectionHealth")).status());
        }
    }

    @Test
    void concurrentProbeAlreadyClaimedDoesNotStartJdbcWork() {
        when(mapper.find(5L)).thenReturn(observation("checking", now.minusSeconds(1), now.plusSeconds(10), 0));
        try (var closeable = closeable(service(100))) {
            Map<String, Object> response = closeable.service.test(source());
            assertEquals("PROBE_BUSY", response.get("failureCode"));
            assertEquals("checking", ((ConnectionHealth) response.get("connectionHealth")).status());
            verifyNoInteractions(jdbc);
        }
    }

    @Test
    void timeoutReturnsPromptlyAndStuckDriverCannotCreateUnboundedWorkers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        when(jdbc.probe(any(), anyInt())).thenAnswer(invocation -> {
            started.countDown();
            boolean finished = false;
            while (!finished) {
                try { finished = release.await(1, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { /* Simulate a driver ignoring cancellation. */ }
            }
            return Map.of();
        });
        try (var closeable = closeable(service(40))) {
            Map<String, Object> first = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> closeable.service.testBeforeSave(source()));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals("TIMEOUT", first.get("failureCode"));
            assertEquals("unavailable", ((ConnectionHealth) first.get("connectionHealth")).status());
            Map<String, Object> second = closeable.service.testBeforeSave(source());
            assertEquals("PROBE_BUSY", second.get("failureCode"));
            assertEquals("unknown", ((ConnectionHealth) second.get("connectionHealth")).status());
            verify(jdbc, times(1)).probe(any(), anyInt());
            verifyNoInteractions(mapper);
        } finally {
            release.countDown();
        }
    }

    @Test
    void unknownExceptionIsRedacted() throws Exception {
        when(jdbc.probe(any(), anyInt())).thenThrow(new SQLException("jdbc:hive2://private:10000/default;token=SECRET"));
        try (var closeable = closeable(service(1000))) {
            Map<String, Object> response = closeable.service.testBeforeSave(source());
            assertEquals("CONNECTION_FAILED", response.get("failureCode"));
            assertFalse(response.toString().contains("SECRET"));
            assertFalse(response.toString().contains("private"));
        }
    }

    private DataSourceHealthService service(long timeout) { return new DataSourceHealthService(mapper, jdbc, clock, 60, timeout, 1); }
    private DataSource source() {
        DataSource source = new DataSource();
        source.setId(5L);
        source.setConnectionRevision(0L);
        return source;
    }
    private DataSourceHealthObservation observation(String status, OffsetDateTime checked, OffsetDateTime expires, long revision) {
        return new DataSourceHealthObservation(5L, revision, status, "token", now, checked, checked, expires, 5L, null, null);
    }
    private ClosingService closeable(DataSourceHealthService service) { return new ClosingService(service); }
    private record ClosingService(DataSourceHealthService service) implements AutoCloseable {
        @Override public void close() { service.close(); }
    }
}
