package com.aetheris.workflow.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SparkResourceQueueServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void admissionLocksRunnerAndChecksAllThreeCapacityDimensions() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet head = mock(ResultSet.class);
        when(head.getLong("id")).thenReturn(41L);
        when(head.getLong("attempt_id")).thenReturn(9L);
        when(head.getLong("environment_id")).thenReturn(3L);
        when(head.getInt("requested_cores")).thenReturn(2);
        when(head.getInt("requested_memory_mb")).thenReturn(1024);
        ResultSet runner = mock(ResultSet.class);
        when(runner.getLong("id")).thenReturn(5L);
        when(runner.getString("runner_key")).thenReturn("runner-a");
        when(runner.getString("base_url")).thenReturn("http://runner-a:18081");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<Object> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(sql.contains("FROM workflow.compute_runner") ? runner : head, 0));
        });
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SparkResourceQueueService.Admission admission = service(jdbc).tryAdmit(9L);

        assertEquals(5L, admission.runnerId());
        assertEquals("runner-a", admission.runnerKey());
        ArgumentCaptor<String> queries = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).query(queries.capture(), any(RowMapper.class), any(Object[].class));
        String capacitySql = queries.getAllValues().get(1).replaceAll("\\s+", " ");
        assertTrue(capacitySql.contains("max_concurrency"));
        assertTrue(capacitySql.contains("requested_cores"));
        assertTrue(capacitySql.contains("requested_memory_mb"));
        assertTrue(capacitySql.contains("FOR UPDATE OF runner SKIP LOCKED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonHeadAttemptCannotJumpTheEnvironmentQueue() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        assertNull(service(jdbc).tryAdmit(12L));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unknownAttemptDoesNotReleaseCapacityUntilItIsReconciled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(0);

        service(jdbc).releaseTerminalAllocations();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());
        assertTrue(!sql.getValue().contains("'unknown'"));
    }

    @Test
    void runnerRegistrationRejectsCredentialBearingOrPathEndpoints() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ManagedSparkCatalogService catalog = mock(ManagedSparkCatalogService.class);
        SparkBatchExecutionAdapter adapter = mock(SparkBatchExecutionAdapter.class);
        SparkResourceQueueService service = new SparkResourceQueueService(
                jdbc, noOpTransactions(), catalog, adapter);

        assertThrows(IllegalArgumentException.class, () -> service.register(
                new SparkResourceQueueService.RunnerInput(
                        1L, "runner-a", "runner", "http://user:pass@host:18081/jobs",
                        4, 4096, 2), "admin"));

        verify(catalog, never()).environment(org.mockito.ArgumentMatchers.anyLong());
    }

    private static SparkResourceQueueService service(JdbcTemplate jdbc) {
        return new SparkResourceQueueService(jdbc, noOpTransactions(),
                mock(ManagedSparkCatalogService.class), mock(SparkBatchExecutionAdapter.class));
    }

    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }
}
