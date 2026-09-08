package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.TaskInstance;
import com.aetheris.workflow.service.ExecutionClaimCoordinator.ExecutionClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
