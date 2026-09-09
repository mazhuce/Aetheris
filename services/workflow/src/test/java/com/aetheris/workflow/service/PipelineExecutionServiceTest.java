package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PipelineExecutionServiceTest {

    @Test
    void rejectsDisabledDependencyBeforeCreatingPipelineRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        Task target = task(11L, "指标汇总", "sql", "enabled");
        Task disabled = task(12L, "订单清洗", "spark", "disabled");
        when(tasks.getById(11L)).thenReturn(target);
        when(tasks.getById(12L)).thenReturn(disabled);
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), mock(TaskExecutionService.class),
                new SyncTaskExecutor(), 120, 120);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.validateRunnableTasks(List.of(12L, 11L)));

        assertTrue(error.getMessage().contains("订单清洗"));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void rejectsUnsupportedTaskTypeBeforeCreatingPipelineRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        when(tasks.getById(11L)).thenReturn(task(11L, "遗留任务", "shell", "enabled"));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), mock(TaskExecutionService.class),
                new SyncTaskExecutor(), 120, 120);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.validateRunnableTasks(List.of(11L)));

        assertTrue(error.getMessage().contains("类型不受支持"));
        verify(jdbc, never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void listScopesOrdinaryUsersAndBoundsLimit() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, mock(TaskService.class), mock(TaskInstanceService.class),
                mock(TaskExecutionService.class), new SyncTaskExecutor(), 120, 120);

        service.list(500, "alice", false);

        verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("WHERE run.triggered_by=? OR task.owner=?"),
                org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    void executionFailureFinalizesCurrentAndPendingNodes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        TaskExecutionService executions = mock(TaskExecutionService.class);
        Task failedTask = new Task();
        failedTask.setId(11L);
        failedTask.setName("事实表加工");
        when(tasks.getById(11L)).thenReturn(failedTask);
        when(executions.execute(anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("执行依赖不可用"));
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));

        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), executions, new SyncTaskExecutor(), 120, 120);

        service.executeRun(7L, "2026-09-08", "admin", List.of(11L, 12L));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='failed'"),
                org.mockito.ArgumentMatchers.eq("执行依赖不可用"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(11L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='skipped'"),
                org.mockito.ArgumentMatchers.eq(7L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='failed',error_message=?"),
                org.mockito.ArgumentMatchers.eq("执行依赖不可用"),
                org.mockito.ArgumentMatchers.eq(7L));
    }

    @Test
    void nodeMessagesAreBoundedToDatabaseColumn() {
        String oversized = "x".repeat(800);
        assertEquals(512, PipelineExecutionService.bounded(oversized, 512).length());
        assertEquals("short", PipelineExecutionService.bounded("short", 512));
    }

    @Test
    void retryRunsOnlyTheSelectedNodeAndItsDownstreamBranches() {
        assertEquals(List.of(12L, 14L), PipelineExecutionService.resumeOrder(
                List.of(10L, 11L, 12L, 13L, 14L), Set.of(12L, 14L), 12L));
    }

    @Test
    void retryRejectsNodeRemovedFromCurrentDependencyGraph() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PipelineExecutionService.resumeOrder(List.of(10L, 11L), Set.of(12L), 12L));

        assertTrue(error.getMessage().contains("已不在当前任务依赖图"));
    }

    @Test
    void retryRejectsUnknownOutcomeToPreventDuplicateRemoteSubmission() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PipelineExecutionService.validateRetrySource("unknown", "unknown"));

        assertTrue(error.getMessage().contains("确认远端执行结果"));
    }

    @Test
    void retryRequiresAnActuallyFailedOrCancelledNode() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PipelineExecutionService.validateRetrySource("failed", "skipped"));

        assertTrue(error.getMessage().contains("失败或已确认取消的节点"));
    }

    @Test
    void staleRunsReleaseActiveRowsAndFinalizeNodes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("RETURNING id"),
                org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(List.of(7L));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, mock(TaskService.class), mock(TaskInstanceService.class),
                mock(TaskExecutionService.class), new SyncTaskExecutor(), 120, 120);

        service.reconcileStaleRuns();

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='unknown'"),
                org.mockito.ArgumentMatchers.eq(7L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("status='skipped'"),
                org.mockito.ArgumentMatchers.eq(7L));
    }

    @Test
    void terminalUnknownSparkResultSuspendsPipelineAndSkipsDownstream() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        TaskExecutionService executions = mock(TaskExecutionService.class);
        Task spark = new Task();
        spark.setId(11L);
        spark.setName("订单清洗");
        spark.setTaskType("spark");
        spark.setTimeoutSeconds(120);
        TaskInstance unknown = new TaskInstance();
        unknown.setId(21L);
        unknown.setStatus("unknown");
        unknown.setFinishedAt(OffsetDateTime.now());
        unknown.setErrorMessage("远端结果无法确认");
        when(tasks.getById(11L)).thenReturn(spark);
        when(executions.execute(11L, "2026-09-09", "pipeline", "admin")).thenReturn(unknown);
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), executions,
                new SyncTaskExecutor(), 120, 120);

        service.executeRun(7L, "2026-09-09", "admin", List.of(11L, 12L));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status=?,instance_id=?"),
                org.mockito.ArgumentMatchers.eq("unknown"), org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("远端结果无法确认"),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(11L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='unknown',error_message=?"),
                org.mockito.ArgumentMatchers.contains("结果待核对"), org.mockito.ArgumentMatchers.eq(7L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='skipped'"),
                org.mockito.ArgumentMatchers.eq(7L));
        verify(executions, never()).execute(12L, "2026-09-09", "pipeline", "admin");
    }

    @Test
    void confirmedSparkCancellationCancelsPipelineAndSkipsDownstream() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TaskService tasks = mock(TaskService.class);
        TaskExecutionService executions = mock(TaskExecutionService.class);
        Task spark = task(11L, "订单清洗", "spark", "enabled");
        spark.setTimeoutSeconds(120);
        TaskInstance cancelled = new TaskInstance();
        cancelled.setId(21L);
        cancelled.setStatus("cancelled");
        cancelled.setFinishedAt(OffsetDateTime.now());
        when(tasks.getById(11L)).thenReturn(spark);
        when(executions.execute(11L, "2026-09-09", "pipeline", "admin")).thenReturn(cancelled);
        doReturn(1).when(jdbc).update(any(String.class), any(Object[].class));
        PipelineExecutionService service = new PipelineExecutionService(
                jdbc, tasks, mock(TaskInstanceService.class), executions,
                new SyncTaskExecutor(), 120, 120);

        service.executeRun(7L, "2026-09-09", "admin", List.of(11L, 12L));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status=?,instance_id=?"),
                org.mockito.ArgumentMatchers.eq("cancelled"), org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(11L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='cancelled',error_message=?"),
                org.mockito.ArgumentMatchers.contains("已确认取消"), org.mockito.ArgumentMatchers.eq(7L));
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("SET status='skipped'"),
                org.mockito.ArgumentMatchers.eq(7L));
        verify(executions, never()).execute(12L, "2026-09-09", "pipeline", "admin");
    }

    private static Task task(long id, String name, String type, String status) {
        Task task = new Task();
        task.setId(id);
        task.setName(name);
        task.setTaskType(type);
        task.setStatus(status);
        return task;
    }
}
