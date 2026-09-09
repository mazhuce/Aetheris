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
    void unknownResultCannotBeReclaimedEvenAfterItsLeaseExpires() {
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
        assertTrue(normalized.contains("status IN ('queued','submitting','running','cancel_requested')"));
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
