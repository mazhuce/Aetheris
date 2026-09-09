package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.TaskInstance;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineCancellationServiceTest {

    @Test
    void springSelectsProductionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
            context.registerBean(SparkBatchExecutionService.class,
                    () -> mock(SparkBatchExecutionService.class));
            context.registerBean(PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
            context.register(PipelineCancellationService.class);
            context.refresh();

            assertNotNull(context.getBean(PipelineCancellationService.class));
        }
    }

    @Test
    void cancelsQueuedPipelineWithoutCallingRemote() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionService sparks = mock(SparkBatchExecutionService.class);
        when(jdbc.queryForList(anyString(), eq(7L)))
                .thenReturn(List.of(Map.of("id", 7L, "status", "queued")),
                        List.of(Map.of("id", 7L, "status", "cancelled")));
        when(jdbc.update(contains("WHERE id=? AND status='queued'"), anyString(), eq(7L)))
                .thenReturn(1);
        PipelineCancellationService service = new PipelineCancellationService(jdbc, sparks, transactions());

        PipelineCancellationService.CancellationResult result = service.cancel(7L, "alice");

        assertFalse(result.pending());
        assertEquals("cancelled", result.run().get("status"));
        verify(jdbc).update(contains("status='skipped'"), eq(7L));
        verify(sparks, never()).cancel(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void keepsPipelineRunningUntilRemoteCancellationIsConfirmed() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionService sparks = mock(SparkBatchExecutionService.class);
        when(jdbc.queryForList(anyString(), eq(7L)))
                .thenReturn(List.of(Map.of("id", 7L, "status", "running")));
        when(jdbc.query(contains("node.status='running'"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(7L))).thenReturn(List.of(
                        new PipelineCancellationService.ActiveNode(8L, 21L, "spark", "订单清洗")));
        TaskInstance pending = new TaskInstance();
        pending.setStatus("cancel_requested");
        when(sparks.cancel(21L)).thenReturn(pending);
        PipelineCancellationService service = new PipelineCancellationService(jdbc, sparks, transactions());

        PipelineCancellationService.CancellationResult result = service.cancel(7L, "alice");

        assertTrue(result.pending());
        assertEquals("running", result.run().get("status"));
        assertEquals(true, result.run().get("cancellation_requested"));
    }

    @Test
    void finalizesPipelineOnlyAfterRemoteConfirmation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionService sparks = mock(SparkBatchExecutionService.class);
        when(jdbc.queryForList(anyString(), eq(7L)))
                .thenReturn(List.of(Map.of("id", 7L, "status", "running")),
                        List.of(Map.of("id", 7L, "status", "cancelled")));
        when(jdbc.query(contains("node.status='running'"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(7L))).thenReturn(List.of(
                        new PipelineCancellationService.ActiveNode(8L, 21L, "spark", "订单清洗")));
        TaskInstance cancelled = new TaskInstance();
        cancelled.setStatus("cancelled");
        when(sparks.cancel(21L)).thenReturn(cancelled);
        when(jdbc.update(contains("SET status='cancelled',finished_at"), eq(8L), eq(7L)))
                .thenReturn(1);
        when(jdbc.update(contains("WHERE id=? AND status='running'"), anyString(), eq(7L)))
                .thenReturn(1);
        PipelineCancellationService service = new PipelineCancellationService(jdbc, sparks, transactions());

        PipelineCancellationService.CancellationResult result = service.cancel(7L, "alice");

        assertFalse(result.pending());
        assertEquals("cancelled", result.run().get("status"));
        verify(jdbc).update(contains("status='skipped'"), eq(7L));
    }

    @Test
    void refusesToPretendSqlCancellationIsReliable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SparkBatchExecutionService sparks = mock(SparkBatchExecutionService.class);
        when(jdbc.queryForList(anyString(), eq(7L)))
                .thenReturn(List.of(Map.of("id", 7L, "status", "running")));
        when(jdbc.query(contains("node.status='running'"), any(org.springframework.jdbc.core.RowMapper.class),
                eq(7L))).thenReturn(List.of(
                        new PipelineCancellationService.ActiveNode(8L, 21L, "sql", "订单汇总")));
        PipelineCancellationService service = new PipelineCancellationService(jdbc, sparks, transactions());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.cancel(7L, "alice"));

        assertTrue(error.getMessage().contains("不支持可靠取消"));
        verify(sparks, never()).cancel(org.mockito.ArgumentMatchers.anyLong());
    }

    @SuppressWarnings("unchecked")
    private static TransactionOperations transactions() {
        TransactionOperations transactions = mock(TransactionOperations.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(null));
        return transactions;
    }
}
