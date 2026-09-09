package com.aetheris.workflow.service;

import com.aetheris.workflow.entity.Task;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
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
}
