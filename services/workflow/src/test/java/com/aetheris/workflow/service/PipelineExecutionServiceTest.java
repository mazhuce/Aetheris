package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import com.aetheris.workflow.entity.TaskInstance;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class PipelineExecutionServiceTest {

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
}
