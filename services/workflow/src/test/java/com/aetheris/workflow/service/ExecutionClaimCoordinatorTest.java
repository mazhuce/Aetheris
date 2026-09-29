package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionClaimCoordinatorTest {

    @Test
    void definitionDigestIsStableSha256() {
        assertEquals(64, ExecutionClaimCoordinator.digest("task-definition").length());
        assertEquals(ExecutionClaimCoordinator.digest("task-definition"),
                ExecutionClaimCoordinator.digest("task-definition"));
    }

    @Test
    void staleWorkerCannotCompleteAnAttempt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60);

        TaskInstance instance = new TaskInstance();
        instance.setId(7L);
        ExecutionClaim stale = new ExecutionClaim(
                instance, 9L, 1, 1L, OffsetDateTime.now().minusSeconds(1));

        assertThrows(ExecutionClaimCoordinator.StaleExecutionException.class,
                () -> coordinator.succeed(stale, 1L, 20L, 20L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeOrUnknownResultCannotBeReclaimedBeforeLeaseRecovery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60);
        Task task = new Task(); task.setId(3L); task.setName("managed");
        task.setTaskType("spark"); task.setJobDefinitionId(9L);

        assertThrows(IllegalStateException.class,
                () -> coordinator.claim(task, "2026-09-09", "manual", "alice", 120));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        String normalized = sql.getValue().replaceAll("\\s+", " ").replaceAll("\\s+\\)", ")");
        assertTrue(normalized.contains("status NOT IN ('queued','submitting','running','cancel_requested','unknown')"));
        assertTrue(!normalized.contains("lease_expires_at < now()"));
    }

    @Test
    void leaseRecoveryTimesOutNeverSubmittedQueueWorkButQuarantinesRemoteWork() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(2);
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60);

        assertEquals(2, coordinator.reconcileExpiredLeases());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());
        String normalized = sql.getValue().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHEN status='queued' THEN 'timed_out' ELSE 'unknown'"));
        assertTrue(normalized.contains("INSERT INTO workflow.execution_event"));
    }

    @Test
    void streamingCapacityCountsUnknownAttemptsUnderEndpointLock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq("http://flink:8083"))).thenReturn(8L);
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60, 8);
        Task task = new Task();
        task.setId(3L);
        task.setTaskType("flink");
        task.setFlinkMode("streaming");
        task.setFlinkEndpoint("http://flink:8083");

        assertThrows(IllegalStateException.class,
                () -> coordinator.claim(task, "2026-09-23", "manual", "alice", 120));

        verify(jdbc).queryForList(anyString(), eq("http://flink:8083"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Long.class), eq("http://flink:8083"));
        assertTrue(sql.getValue().contains("'unknown'"));
    }

    @Test
    void liveStreamRenewalUpdatesAttemptAndInstanceLeaseTogether() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60);

        coordinator.renewFlinkLease(7L, 9L, "session/operation");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().getFirst().contains("ea.remote_handle=?"));
        assertTrue(sql.getAllValues().getFirst().contains("ea.flink_job_id IS NULL"));
        assertTrue(sql.getAllValues().get(1).contains("active_attempt_id=?"));
    }

    @Test
    void replacementResumeAdvancesDefinitionVersionAndWritesAuditEvent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60);
        Task task = new Task(); task.setId(3L); task.setDefinitionVersion(4);
        String replacement = "INSERT INTO sink SELECT id + 0 FROM source";

        assertEquals(5, coordinator.persistFlinkResume(7L, 9L, task, "session/operation",
                replacement, ExecutionClaimCoordinator.digest(replacement)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(4)).update(sql.capture(), any(Object[].class));
        assertTrue(sql.getAllValues().get(0).contains("flink_lifecycle_state='resuming'"));
        assertTrue(sql.getAllValues().get(1).contains("definition_version=definition_version+1"));
        assertTrue(sql.getAllValues().get(2).contains("INSERT INTO workflow.execution_event"));
        assertTrue(sql.getAllValues().get(3).contains("active_attempt_id=?"));
    }

    @Test
    void replacementResumeRejectsConcurrentDefinitionChange() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("UPDATE workflow.task\n") ? 0 : 1;
        });
        ExecutionClaimCoordinator coordinator = new ExecutionClaimCoordinator(
                jdbc, noOpTransactions(), new ObjectMapper(), "worker-test", 60);
        Task task = new Task(); task.setId(3L); task.setDefinitionVersion(4);
        String replacement = "INSERT INTO sink SELECT id + 0 FROM source";

        assertThrows(IllegalStateException.class, () -> coordinator.persistFlinkResume(
                7L, 9L, task, "session/operation", replacement,
                ExecutionClaimCoordinator.digest(replacement)));
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    private static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
